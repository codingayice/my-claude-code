package cn.ayice.veyra.server;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Veyra 本地 Agent HTTP 服务，仅扫描 Harness 根包。
 */
@SpringBootApplication(scanBasePackages = "cn.ayice.veyra")
public class AgentServerApplication {
}
