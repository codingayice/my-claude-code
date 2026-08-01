package cn.ayice.veyra.conversation.context.systemprompt;

import cn.ayice.veyra.conversation.context.instruction.ProjectInstructionLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 加载用户和工作区指令文件的系统提示词片段，与跨会话长期记忆保持独立。
 */
public final class ProjectInstructionSection extends SystemPromptSection {

    private static final Logger log = LoggerFactory.getLogger(ProjectInstructionSection.class);

    /**
     * 创建每次请求重新读取的项目指令片段，使文件更新无需重启即可生效。
     */
    public ProjectInstructionSection() {
        super("project-instructions", true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        try {
            String instructions = ProjectInstructionLoader.defaults(ctx.workingDir()).load();
            if (instructions == null || instructions.isBlank()) {
                return null;
            }
            return "# Project instructions\n\n" + instructions.trim();
        } catch (Exception error) {
            log.error("加载项目指令失败, workspace={}", ctx.workingDir(), error);
            return null;
        }
    }
}
