package cn.ayice.veyra.runtime;

/**
 * Read-only slash command suggestion exposed by the runtime host.
 */
public record CommandOption(
        String id,
        String name,
        String description,
        String command
) {
}
