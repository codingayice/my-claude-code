package cn.ayice.veyra.control.service;

import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.RunSubmission;
import cn.ayice.veyra.control.dto.run.CreateRunResponse;
import org.springframework.stereotype.Service;

/**
 * 运行应用服务。一次 run 对应一次用户输入触发的 ChatLoop 或 AgentLoop。
 */
@Service
public class RunApplicationService {

    private final RuntimeHost runtimeHost;

    /**
     * 注入该服务运行所需依赖并创建 RunApplicationService。
     */
    public RunApplicationService(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 根据输入创建对应对象。
     */
    public CreateRunResponse createRun(String sessionId, String input, String mode) {
        RunSubmission submission = runtimeHost.submitRun(sessionId, input, mode);
        return new CreateRunResponse(submission.runId(), submission.accepted());
    }
}
