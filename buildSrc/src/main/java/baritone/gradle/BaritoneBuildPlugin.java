package baritone.gradle;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class BaritoneBuildPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // This plugin currently does nothing but satisfy the java-gradle-plugin requirement.
        // Baritone build tasks are currently referenced directly by class name in subprojects.
    }
}
