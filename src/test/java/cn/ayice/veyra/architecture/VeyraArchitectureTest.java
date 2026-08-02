package cn.ayice.veyra.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "cn.ayice.veyra",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class VeyraArchitectureTest {

    @ArchTest
    static final ArchRule control_does_not_bypass_runtime_into_harness_implementations = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.control..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.context..",
                    "cn.ayice.veyra.compaction..",
                    "cn.ayice.veyra.memory..",
                    "cn.ayice.veyra.tool..",
                    "cn.ayice.veyra.subagent..",
                    "cn.ayice.veyra.interaction..",
                    "cn.ayice.veyra.llm..",
                    "cn.ayice.veyra.session.persistence..",
                    "cn.ayice.veyra.session.recovery.."
            );

    @ArchTest
    static final ArchRule session_does_not_depend_on_control_or_spring = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.session..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule runtime_does_not_depend_on_control_or_spring = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule context_does_not_depend_on_compaction = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.context..")
            .should().dependOnClassesThat().resideInAnyPackage("cn.ayice.veyra.compaction..");

    @ArchTest
    static final ArchRule memory_does_not_depend_on_runtime_or_subagent = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.memory..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.subagent.."
            );

    @ArchTest
    static final ArchRule persisted_session_module_does_not_depend_on_runtime = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.session..")
            .should().dependOnClassesThat().resideInAnyPackage("cn.ayice.veyra.runtime..");

    @ArchTest
    static final ArchRule harness_modules_do_not_depend_on_control_boot_server_or_spring = noClasses()
            .that().resideInAnyPackage(
                    "cn.ayice.veyra.session..",
                    "cn.ayice.veyra.context..",
                    "cn.ayice.veyra.compaction..",
                    "cn.ayice.veyra.memory..",
                    "cn.ayice.veyra.tool..",
                    "cn.ayice.veyra.subagent..",
                    "cn.ayice.veyra.interaction..",
                    "cn.ayice.veyra.llm.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.boot..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule removed_legacy_packages_must_not_return = noClasses()
            .should().resideInAnyPackage(
                    "cn.ayice.veyra.conversation..",
                    "cn.ayice.veyra.kernel..",
                    "cn.ayice.veyra.host..",
                    "cn.ayice.veyra.tooling..",
                    "cn.ayice.veyra.permission..",
                    "cn.ayice.veyra.transport.."
            );

    @ArchTest
    static final ArchRule tool_does_not_depend_on_runtime_session_memory_or_subagent = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.tool..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.session..",
                    "cn.ayice.veyra.memory..",
                    "cn.ayice.veyra.subagent.."
            );

    @ArchTest
    static final ArchRule transcript_persistence_does_not_depend_on_runtime_host_or_http = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.session.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule server_contains_only_the_spring_boot_entry_point = classes()
            .that().resideInAnyPackage("cn.ayice.veyra.server..")
            .should().haveSimpleName("AgentServerApplication");
}
