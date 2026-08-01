package cn.ayice.veyra.architecture;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Veyra 生产代码的注释完整性测试，防止新增无职责说明的类型和方法。
 */
class VeyraDocumentationTest {

    private static final Pattern MEANINGLESS_TEMPLATE = Pattern.compile(String.join("|", List.of(
            "对应的内部职责",
            "对应的数据",
            "所表达的条件是否成立",
            "对应的状态或数据",
            "对应的结果",
            "所需的信息",
            "定义的处理流程",
            "当前组件定义的核心操作",
            "使用给定依赖和初始状态创建当前对象",
            "定义该模块使用的数据或行为契约",
            "返回或处理当前对象中的",
            "返回当前对象记录的",
            "将当前数据转换为",
            "根据当前输入构建er",
            "当当前命令实现支持给定输入时返回 true",
            "使用给定字段创建",
            "封装该模块内部使用的状态与操作",
            "expected: <"
    )));

    /**
     * 检查所有命名类型和方法都具有 Javadoc，且不使用无信息模板。
     *
     * @throws IOException 读取生产源码失败时抛出
     */
    @Test
    void allNamedTypesAndMethodsHaveJavadoc() throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Path sourceRoot = Path.of("src/main/java/cn/ayice/veyra");
        List<Path> sourceFiles;
        try (var paths = Files.walk(sourceRoot)) {
            sourceFiles = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        List<String> missing = new ArrayList<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                null,
                null,
                StandardCharsets.UTF_8
        )) {
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-proc:none"),
                    null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles)
            );
            DocTrees docTrees = DocTrees.instance(task);

            // 只解析语法树和源码注释，不执行注解处理或生成 class 文件。
            for (CompilationUnitTree unit : task.parse()) {
                new TreePathScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree node, Void unused) {
                        if (!node.getSimpleName().isEmpty()) {
                            validateDocumentation(unit, node.getSimpleName().toString());
                        }
                        return super.visitClass(node, unused);
                    }

                    @Override
                    public Void visitMethod(MethodTree node, Void unused) {
                        if (node.getName().contentEquals("<init>")) {
                            return super.visitMethod(node, unused);
                        }
                        String name = node.getName().contentEquals("<init>")
                                ? "构造器"
                                : node.getName().toString();
                        validateDocumentation(unit, name);
                        return super.visitMethod(node, unused);
                    }

                    private void validateDocumentation(CompilationUnitTree source, String symbol) {
                        var documentation = docTrees.getDocCommentTree(getCurrentPath());
                        if (documentation == null) {
                            missing.add(location(source, symbol) + " 缺少 Javadoc");
                            return;
                        }
                        if (MEANINGLESS_TEMPLATE.matcher(documentation.toString()).find()) {
                            missing.add(location(source, symbol) + " 使用无信息模板");
                        }
                    }

                    private String location(CompilationUnitTree source, String symbol) {
                        long position = docTrees.getSourcePositions().getStartPosition(source, getCurrentPath().getLeaf());
                        long line = source.getLineMap().getLineNumber(position);
                        return "%s:%d %s".formatted(source.getSourceFile().getName(), line, symbol);
                    }
                }.scan(unit, null);
            }
        }

        assertTrue(missing.isEmpty(), () -> "以下类型或方法的 Javadoc 不合格:\n" + String.join("\n", missing));
    }
}
