package org.twdata.maven.itblast;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

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
    private final Map<String, Container> idToContainerMap = new HashMap<String, Container>()
    {{
            put("tomcat5x", new Container("tomcat5x", "org.apache.tomcat", "apache-tomcat", "5.5.26"));
            put("tomcat6x", new Container("tomcat6x", "org.apache.tomcat", "apache-tomcat", "6.0.20"));
            put("resin3x", new Container("resin3x", "com.caucho", "resin", "3.0.26"));
            put("jboss42x", new Container("jboss42x", "org.jboss.jbossas", "jbossas", "4.2.3.GA"));
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
    private int httpPort = 0;

    /**
     * RMI port for cargo to control the container
     *
     * @parameter expression="${rmi.port}"
     */
    private int rmiPort = 0;

    /**
     * Project build directory
     *
     * @parameter expression="${project.build.directory}"
     * @required
     */
    private File projectBuildDirectory;

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
        if (skip || skipTests)
        {
            getLog().info("Integration tests are skipped.");
            return;
        }
        MojoExecutionException surefireException = null;
        String[] containerIds = containers.split(",");
        for (int x = 0; x < containerIds.length; x++)
        {
            String containerId = containerIds[x];
            boolean isLastContainer = (x == containerIds.length - 1);
            Container container = idToContainerMap.get(containerId);
            if (container == null)
            {
                throw new IllegalArgumentException("Container " + containerId + " not supported");
            }

            File containerDir = new File(project.getBuild().getDirectory() + "/container/" + container.getId());

            // retrieve non-embedded containers
            if (!"embedded".equals(container.getType()))
            {
                if (containerDir.exists())
                {
                    getLog().info("Reusing unpacked container '" + container.getId() + "' from " + containerDir.getPath());
                }
                else
                {
                    getLog().info("Unpacking container '" + container.getId() + "' from container artifact: " + container.toString());
                        unpackContainer(container, containerDir.getPath());
                }
            }

            int actualHttpPort = pickFreePort(httpPort);
            int actualRmiPort = pickFreePort(rmiPort);
            getLog().info("Running integration tests on the " + container.getId() + " container on ports "
                    + actualHttpPort + " (http) and " + actualRmiPort + " (rmi)");
            Plugin cargoPlugin = plugin(
                    groupId("org.codehaus.cargo"),
                    artifactId("cargo-maven2-plugin"),
                    version("1.0-beta-2")
            );
            ExecutionEnvironment env = executionEnvironment(project, session, pluginManager);

            executeMojo(
                    cargoPlugin,
                    goal("start"),
                    new Xpp3Dom(buildCargoConfig(container, containerDir, actualHttpPort, actualRmiPort)),
                    env
            );

            try
            {
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
                                                element(name("value"), String.valueOf(actualHttpPort))
                                        )
                                ),
                                element(name("reportsDirectory"), "${project.build.directory}/" + container.getId() + "/surefire-reports")
                        ),
                        env
                );
            }
            catch (MojoExecutionException ex)
            {
                String msg = "Unable to execute tests for container " + container.getId() + ": " + ex.getMessage();
                if (!isLastContainer)
                {
                    msg += ", continuing to run tests against other containers";
                }
                getLog().error(msg);
                surefireException = ex;
            }

            File base = new File(projectBuildDirectory, container.getId());
            File source = new File(base, "surefire-reports");
            File dest = new File(projectBuildDirectory, "surefire-reports");
            renameAndCopyTests(source, dest, container.getId());

            executeMojo(
                    cargoPlugin,
                    goal("stop"),
                    new Xpp3Dom(buildCargoConfig(container, containerDir, actualHttpPort, actualRmiPort)),
                    env
            );

            // throw the saved surefire exception
            if (isLastContainer && surefireException != null)
            {
                throw surefireException;
            }
        }
    }

    private int pickFreePort(int requestedPort)
    {
        if (requestedPort > 0)
        {
            return requestedPort;
        }
        ServerSocket socket = null;
        try
        {
            socket = new ServerSocket(0);
            return socket.getLocalPort();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Error opening socket", e);
        }
        finally
        {
            if (socket != null)
            {
                try
                {
                    socket.close();
                }
                catch (IOException e)
                {
                    throw new RuntimeException("Error closing socket", e);
                }
            }
        }
    }

    private Xpp3Dom buildCargoConfig(Container container, File containerDir, int actualHttpPort, int actualRmiPort)
    {
        return configuration(
                element(name("wait"), Boolean.toString(wait)),
                element(name("container"),
                        element(name("containerId"), container.getId()),
                        element(name("type"), container.getType()),
                        element(name("home"), containerDir.getPath() + "/" + container.getArtifactId() + "-" + container.getVersion()),
                        //element(name("output"), "${project.build.directory}/"+container.getId()+"/output-"+identifier+".log"),
                        //element(name("log"), "${project.build.directory}/"+container.getId()+"/cargo-"+identifier+".log"),
                        element(name("systemProperties"),
                                element(name("org.apache.commons.logging.Log"), "org.apache.commons.logging.impl.SimpleLog")
                        )
                ),
                element(name("configuration"),
                        element(name("home"), containerDir.getPath() + "/cargo-home"),
                        element(name("type"), "standalone"),
                        element(name("properties"),
                                element(name("cargo.servlet.port"), String.valueOf(actualHttpPort)),
                                element(name("cargo.rmi.port"), String.valueOf(actualRmiPort))

                        )
                )
        );
    }

    private void unpackContainer(final Container container, String outputDirectory) throws MojoExecutionException
    {
        executeMojo(
            plugin(
                    groupId("org.apache.maven.plugins"),
                    artifactId("maven-dependency-plugin"),
                    version("2.0")
            ),
            goal("unpack"),
            configuration(
                    element(name("artifactItems"),
                            element(name("artifactItem"),
                                    element(name("groupId"), container.getGroupId()),
                                    element(name("artifactId"), container.getArtifactId()),
                                    element(name("version"), container.getVersion()),
                                    element(name("type"), "zip"))),
                    element(name("outputDirectory"), outputDirectory)
            ),
            executionEnvironment(project, session, pluginManager));

    }

    void renameAndCopyTests(File source, File dest, String container)
    {
        if (!source.exists())
        {
            return;
        }
        else
        {
            if (!dest.exists())
            {
                dest.mkdir();
            }
        }
        for (File test : source.listFiles(new FilenameFilter()
        {
            public boolean accept(File file, String s)
            {
                return s.startsWith("TEST-") && s.endsWith(".xml");
            }
        }))
        {

            String testName = test.getName().substring("TEST-".length(), test.getName().length() - ".xml".length());
            String betterTestName = testName + "OnContainer" + container;

            File target = new File(dest, "TEST-" + betterTestName + ".xml");
            BufferedReader fin = null;
            PrintWriter fout = null;
            try
            {
                fin = new BufferedReader(new InputStreamReader(new FileInputStream(test)));
                fout = new PrintWriter(new FileWriter(target));
                String line;
                while ((line = fin.readLine()) != null)
                {
                    fout.println(line.replaceAll(testName, betterTestName));
                }
            }
            catch (IOException ex)
            {
                ex.printStackTrace();
            }
            finally
            {

                try
                {
                    if (fin != null)
                    {
                        fin.close();
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
                if (fout != null)
                {
                    fout.close();
                }
            }
        }
    }


    private static class Container
    {
        private final String id;
        private final String type;
        private final String groupId;
        private final String artifactId;
        private final String version;

        public Container(String id, String groupId, String artifactId, String version)
        {
            this.id = id;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.type = "installed";
        }

        public Container(String id)
        {
            this.id = id;
            this.type = "embedded";
            this.groupId = null;
            this.artifactId = null;
            this.version = null;
        }

        public String getId()
        {
            return id;
        }

        public String getType()
        {
            return type;
        }

        public String getGroupId()
        {
            return groupId;
        }

        public String getArtifactId()
        {
            return artifactId;
        }

        public String getVersion()
        {
            return version;
        }
    }
}
