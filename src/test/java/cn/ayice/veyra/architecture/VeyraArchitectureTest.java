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
    static final ArchRule control_only_enters_runtime_through_host = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.control..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.kernel..",
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.conversation.transcript..",
                    "cn.ayice.veyra.tooling..",
                    "cn.ayice.veyra.tooling.permission..",
                    "cn.ayice.veyra.conversation.context..",
                    "cn.ayice.veyra.conversation.memory..",
                    "cn.ayice.veyra.llm..",
                    "cn.ayice.veyra.interaction.."
            );

    @ArchTest
    static final ArchRule host_does_not_depend_on_control_or_spring = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.host..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule kernel_does_not_depend_on_host_control_or_spring = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.host..",
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule tooling_does_not_depend_on_higher_level_subsystems = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.tooling..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.kernel..",
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.conversation..",
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.host..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule removed_legacy_packages_must_not_return = noClasses()
            .should().resideInAnyPackage(
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.context..",
                    "cn.ayice.veyra.memory..",
                    "cn.ayice.veyra.session..",
                    "cn.ayice.veyra.tool..",
                    "cn.ayice.veyra.permission..",
                    "cn.ayice.veyra.transport.."
            );

    @ArchTest
    static final ArchRule conversation_does_not_depend_on_execution_control_or_spring = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.conversation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.runtime..",
                    "cn.ayice.veyra.kernel..",
                    "cn.ayice.veyra.tooling..",
                    "cn.ayice.veyra.host..",
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule transcript_persistence_does_not_depend_on_runtime_host_or_http = noClasses()
            .that().resideInAnyPackage("cn.ayice.veyra.conversation.transcript..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "cn.ayice.veyra.host..",
                    "cn.ayice.veyra.control..",
                    "cn.ayice.veyra.server..",
                    "org.springframework.."
            );

    @ArchTest
    static final ArchRule server_contains_only_the_spring_boot_entry_point = classes()
            .that().resideInAnyPackage("cn.ayice.veyra.server..")
            .should().haveSimpleName("AgentServerApplication");
}
