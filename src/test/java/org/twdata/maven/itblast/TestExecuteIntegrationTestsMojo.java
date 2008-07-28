package org.twdata.maven.itblast;

import junit.framework.TestCase;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;

public class TestExecuteIntegrationTestsMojo extends TestCase
{
    File source;
    File dest;

    @Override
    public void setUp() {
        File tmp = new File(System.getProperty("java.io.tmpdir"));
        source = new File(tmp, "source");
        source.mkdir();
        dest = new File(tmp, "dest");
        dest.mkdir();
    }

    @Override
    public void tearDown() throws IOException
    {
        FileUtils.deleteDirectory(source);
        FileUtils.deleteDirectory(dest);
    }
    public void testRenameAndCopyTests() throws IOException
    {
        ExecuteIntegrationTestsMojo mojo = new ExecuteIntegrationTestsMojo();

        FileUtils.writeStringToFile(new File(source, "TEST-my.FooTest.xml"), "foo-my.FooTest-bar");
        mojo.renameAndCopyTests(source, dest, "tomcat5x");
        assertEquals(1, dest.listFiles().length);
        assertEquals("TEST-my.FooTestOnContainertomcat5x.xml", dest.listFiles()[0].getName());
        assertEquals("foo-my.FooTestOnContainertomcat5x-bar", FileUtils.readLines(dest.listFiles()[0]).get(0));
    }
}
