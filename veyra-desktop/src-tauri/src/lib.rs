use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Mutex;
use std::time::UNIX_EPOCH;

use serde::Serialize;
use tauri::{Emitter, Manager};
use tauri_plugin_log::{Target, TargetKind};
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;

struct AgentState {
    child: Mutex<Option<tokio::process::Child>>,
}

fn project_root() -> Result<PathBuf, String> {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .map(Path::to_path_buf)
        .ok_or_else(|| "Failed to resolve Veyra project root".to_string())
}

fn veyra_root() -> Result<PathBuf, String> {
    env::var_os("USERPROFILE")
        .or_else(|| env::var_os("HOME"))
        .map(PathBuf::from)
        .map(|home| home.join(".veyra"))
        .ok_or_else(|| "Failed to resolve the user home directory".to_string())
}

fn preferences_path() -> Result<PathBuf, String> {
    Ok(veyra_root()?.join("preferences.json"))
}

fn read_preferences() -> Result<HashMap<String, String>, String> {
    let path = preferences_path()?;
    if !path.exists() {
        return Ok(HashMap::new());
    }
    let content = fs::read_to_string(&path)
        .map_err(|error| format!("Failed to read Veyra preferences: {error}"))?;
    serde_json::from_str(&content)
        .map_err(|error| format!("Failed to parse Veyra preferences: {error}"))
}

#[tauri::command]
fn preference_get(key: String) -> Result<Option<String>, String> {
    Ok(read_preferences()?.remove(&key))
}

#[tauri::command]
fn preference_set(key: String, value: String) -> Result<(), String> {
    if key.is_empty() || key.len() > 128 {
        return Err("Invalid preference key".to_string());
    }
    let path = preferences_path()?;
    let parent = path
        .parent()
        .ok_or_else(|| "Failed to resolve Veyra preferences directory".to_string())?;
    fs::create_dir_all(parent)
        .map_err(|error| format!("Failed to create Veyra preferences directory: {error}"))?;
    let mut preferences = read_preferences()?;
    preferences.insert(key, value);
    let content = serde_json::to_vec_pretty(&preferences)
        .map_err(|error| format!("Failed to serialize Veyra preferences: {error}"))?;
    fs::write(&path, content)
        .map_err(|error| format!("Failed to write Veyra preferences: {error}"))
}

#[derive(Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum ResourceTreeEntry {
    Folder {
        id: String,
        path: String,
        name: String,
        children: Vec<ResourceTreeEntry>,
        expanded: bool,
    },
    File {
        id: String,
        path: String,
        name: String,
        #[serde(rename = "type")]
        file_type: String,
        #[serde(rename = "createdAt")]
        created_at: u128,
    },
}

fn path_to_string(path: &Path) -> String {
    path.to_string_lossy().to_string()
}

fn path_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().to_string())
        .unwrap_or_else(|| path_to_string(path))
}

fn is_word_document(path: &Path) -> bool {
    path.extension()
        .and_then(|extension| extension.to_str())
        .map(|extension| extension.eq_ignore_ascii_case("docx"))
        .unwrap_or(false)
}

fn modified_millis(metadata: &fs::Metadata) -> u128 {
    metadata
        .modified()
        .ok()
        .and_then(|modified| modified.duration_since(UNIX_EPOCH).ok())
        .map(|duration| duration.as_millis())
        .unwrap_or(0)
}

fn build_resource_tree(
    path: &Path,
    expanded_folders: &HashSet<String>,
) -> Result<Vec<ResourceTreeEntry>, String> {
    let entries = fs::read_dir(path).map_err(|error| format!("Failed to read folder: {}", error))?;
    let mut tree_entries = Vec::new();

    for entry in entries {
        let entry = match entry {
            Ok(entry) => entry,
            Err(error) => {
                eprintln!("[veyra-desktop] Failed to read folder entry: {}", error);
                continue;
            }
        };

        let child_path = entry.path();
        let metadata = match entry.metadata() {
            Ok(metadata) => metadata,
            Err(error) => {
                eprintln!(
                    "[veyra-desktop] Failed to read file metadata: {}: {}",
                    path_to_string(&child_path),
                    error
                );
                continue;
            }
        };

        if metadata.is_dir() {
            let children = match build_resource_tree(&child_path, expanded_folders) {
                Ok(children) => children,
                Err(error) => {
                    eprintln!(
                        "[veyra-desktop] Failed to read subfolder: {}: {}",
                        path_to_string(&child_path),
                        error
                    );
                    continue;
                }
            };

            if children.is_empty() {
                continue;
            }

            let child_path_string = path_to_string(&child_path);
            tree_entries.push(ResourceTreeEntry::Folder {
                id: child_path_string.clone(),
                path: child_path_string.clone(),
                name: path_name(&child_path),
                children,
                expanded: expanded_folders.contains(&child_path_string),
            });
            continue;
        }

        if !metadata.is_file() || !is_word_document(&child_path) {
            continue;
        }

        let child_path_string = path_to_string(&child_path);
        tree_entries.push(ResourceTreeEntry::File {
            id: child_path_string.clone(),
            path: child_path_string,
            name: path_name(&child_path),
            file_type: "word".to_string(),
            created_at: modified_millis(&metadata),
        });
    }

    tree_entries.sort_by(|left, right| {
        let left_is_folder = matches!(left, ResourceTreeEntry::Folder { .. });
        let right_is_folder = matches!(right, ResourceTreeEntry::Folder { .. });

        match (left_is_folder, right_is_folder) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => entry_name(left).cmp(entry_name(right)),
        }
    });

    Ok(tree_entries)
}

fn entry_name(entry: &ResourceTreeEntry) -> &str {
    match entry {
        ResourceTreeEntry::Folder { name, .. } => name,
        ResourceTreeEntry::File { name, .. } => name,
    }
}

#[tauri::command]
fn read_resource_tree(
    root_path: String,
    expanded_folders: Vec<String>,
) -> Result<Vec<ResourceTreeEntry>, String> {
    let root = Path::new(&root_path);
    if !root.is_dir() {
        return Err("Selected path is not a folder".into());
    }

    let expanded_folders = expanded_folders.into_iter().collect::<HashSet<_>>();
    build_resource_tree(root, &expanded_folders)
}

#[tauri::command]
async fn agent_start(state: tauri::State<'_, AgentState>, app: tauri::AppHandle) -> Result<String, String> {
    {
        let mut guard = state.child.lock().unwrap();
        if let Some(child) = guard.as_mut() {
            match child.try_wait() {
                Ok(Some(_status)) => {
                    *guard = None;
                }
                Ok(None) => return Ok("agent already running".into()),
                Err(_error) => {
                    *guard = None;
                }
            }
        }
    }

    let project_root = project_root()?;
    let classes_path = project_root.join("target/classes");
    let classpath_file = project_root.join("target/classpath.txt");
    let dependency_classpath = fs::read_to_string(&classpath_file)
        .map_err(|error| format!("Failed to read agent classpath: {}", error))?;
    let classpath = format!("{};{}", classes_path.display(), dependency_classpath.trim());

    let mut child = Command::new("java")
        .args([
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-cp",
            &classpath,
            "cn.ayice.Main",
            "--http",
            "--port",
            "17361",
        ])
        .current_dir(&project_root)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true)
        .spawn()
        .map_err(|e| format!("Failed to start agent: {}", e))?;

    let stdout = child.stdout.take().ok_or("Failed to capture stdout")?;
    let stderr = child.stderr.take().ok_or("Failed to capture stderr")?;

    *state.child.lock().unwrap() = Some(child);

    let app_clone = app.clone();
    tokio::spawn(async move {
        let mut lines = BufReader::new(stdout).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let _ = app_clone.emit("agent-log", line);
        }
    });

    tokio::spawn(async move {
        let mut lines = BufReader::new(stderr).lines();
        while let Ok(Some(line)) = lines.next_line().await {
            let _ = app.emit("agent-error", line);
        }
    });

    Ok("agent started".into())
}

#[tauri::command]
async fn agent_stop(state: tauri::State<'_, AgentState>) -> Result<String, String> {
    let mut guard = state.child.lock().unwrap();
    if let Some(mut child) = guard.take() {
        let _ = child.start_kill();
    }
    Ok("agent stopped".into())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .manage(AgentState {
            child: Mutex::new(None),
        })
        .invoke_handler(tauri::generate_handler![
            agent_start,
            agent_stop,
            read_resource_tree,
            preference_get,
            preference_set
        ])
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_fs::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            if cfg!(debug_assertions) {
                let log_dir = app.path().home_dir()?.join(".veyra").join("logs");
                app.handle().plugin(
                    tauri_plugin_log::Builder::default()
                        .level(log::LevelFilter::Info)
                        .clear_targets()
                        .targets([
                            Target::new(TargetKind::Stdout),
                            Target::new(TargetKind::Folder {
                                path: log_dir,
                                file_name: Some("veyra-desktop".into()),
                            }),
                        ])
                        .build(),
                )?;
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
