package org.twdata.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.Map;
import java.util.HashMap;

/**
 * Run functional tests
 *
 * @requiresDependencyResolution execute
 * @goal execute
 * @phase integration-test
 */
public class ExecuteIntegrationTestsMojo
    extends AbstractMojo
{
    private final Map<String,Container> idToContainerMap = new HashMap<String,Container>() {{
        put("tomcat5x", new Container("tomcat5x", "https://m2proxy.atlassian.com/repository/public/org/apache/tomcat/apache-tomcat/5.5.25/apache-tomcat-5.5.25.zip"));
        put("resin3x", new Container("resin3x", "http://www.caucho.com/download/resin-3.0.26.zip"));
        put("jboss42x", new Container("jboss42x", "http://internode.dl.sourceforge.net/sourceforge/jboss/jboss-4.2.3.GA.zip"));
        put("jetty6x", new Container("jetty6x"));
    }};

    /**
     * List of containers to test against
     *
     * @parameter expression="${containers}"
     */
    private String containers = "tomcat5x";

    /**
     * HTTP port for the servlet containers
     *
     * @parameter expression="${http.port}"
     */
    private int httpPort = 8080;

    /**
     * RMI port for cargo to control the container
     *
     * @parameter expression="${rmi.port}"
     */
    private int rmiPort = 10232;

    /**
     * Servlet container wait
     *
     * @parameter expression="${wait}"
     */
    private boolean wait;

    /**
     * Set this to 'true' to skip running tests, but still compile them. Its use is NOT RECOMMENDED, but quite
     * convenient on occasion.
     *
     * @parameter expression="${skipTests}"
     */
    private boolean skipTests;

    /**
     * Set this to 'true' to bypass unit tests entirely. Its use is NOT RECOMMENDED, especially if you
     * enable it using the "maven.test.skip" property, because maven.test.skip disables both running the
     * tests and compiling the tests.  Consider using the skipTests parameter instead.
     *
     * @parameter expression="${maven.test.skip}"
     */
    private boolean skip;

    /**
     * Pattern for to use to find integration tests
     *
     * @parameter expression="${functionalTestPattern}"
     */
    private String functionalTestPattern = "it/**";

    /**
     * The Maven Project Object
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * The Maven Session Object
     *
     * @parameter expression="${session}"
     * @required
     * @readonly
     */
    protected MavenSession session;

    /**
     * The Maven PluginManager Object
     *
     * @component
     * @required
     */
    protected PluginManager pluginManager;

    public void execute()
        throws MojoExecutionException
    {
        if (skip || skipTests) {
            getLog().info( "Integration tests are skipped." );
            return;
        }
        for (String containerId : containers.split(",")) {
            Container container = idToContainerMap.get(containerId);
            if (container == null) {
                throw new IllegalArgumentException("Container "+containerId+" not supported");
            }
            getLog().info("Running integration tests on the "+container.getId()+" container");
            Plugin cargoPlugin = plugin(
                            groupId("org.codehaus.cargo"),
                            artifactId("cargo-maven2-plugin"),
                            version("1.0-alpha-5")
                    );
            Xpp3Dom cargoConfig = configuration(
                            element(name("wait"), Boolean.toString(wait)),
                            element(name("container"),
                                    element(name("containerId"), container.getId()),
                                    element(name("type"), container.getType()),
                                    element(name("zipUrlInstaller"),
                                            element(name("url"), container.getUrl())
                                    ),
                                    element(name("output"), "${project.build.directory}/"+container.getId()+"/output.log"),
                                    element(name("log"), "${project.build.directory}/"+container.getId()+"/cargo.log"),
                                    element(name("systemProperties"),
                                            element(name("org.apache.commons.logging.Log"), "org.apache.commons.logging.impl.SimpleLog")
                                    )
                            ),
                            element(name("configuration"),
                                    element(name("home"), "${project.build.directory}/"+container.getId()+"/server"),
                                    element(name("properties"),
                                                element(name("cargo.servlet.port"), String.valueOf(httpPort)),
                                                element(name("cargo.rmi.port"), String.valueOf(rmiPort))
                                    )
                            )
                    );
            ExecutionEnvironment env = executionEnvironment(project, session, pluginManager);
            
            executeMojo(
                    cargoPlugin,
                    goal("start"),
                    new Xpp3Dom(cargoConfig),
                    env
                );

            executeMojo(
                    plugin(
                            groupId("org.apache.maven.plugins"),
                            artifactId("maven-surefire-plugin")
                    ),
                    goal("test"),
                    configuration(
                            element(name("includes"),
                                    element(name("include"), functionalTestPattern)
                            ),
                            element(name("excludes"),
                                    element(name("exclude"), "**/*$*")
                            ),
                            element(name("systemProperties"),
                                    element(name("property"),
                                            element(name("name"), "http.port"),
                                            element(name("value"), String.valueOf(httpPort))
                                    )
                            ),
                            element(name("reportsDirectory"), "${project.build.directory}/"+container.getId()+"/surefire-reports")
                    ),
                    env
                );

            executeMojo(
                    cargoPlugin,
                    goal("stop"),
                    new Xpp3Dom(cargoConfig),
                    env
                );
        }
    }

    private static class Container {
        private final String id;
        private final String type;
        private final String url;

        public Container(String id, String url) {
            this.id = id;
            this.type = "installed";
            this.url = url;
        }

        public Container(String id) {
            this.id = id;
            this.type = "embedded";
            this.url = null;
        }

        public String getId() {
            return id;
        }

        public String getType() {
            return type;
        }

        public String getUrl() {
            return url;
        }
    }
}
