package org.overture.fmi.ide.fmuexport.xml;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.Assert;
import org.junit.Test;
import org.overturetool.fmi.AbortException;
import org.overturetool.fmi.Main;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ExportFmuTest
{
	public static String getCurrentClassAndMethodNames()
	{
		final StackTraceElement e = Thread.currentThread().getStackTrace()[2];
		final String s = e.getClassName();
		return s.substring(s.lastIndexOf('.') + 1, s.length()) + "."
				+ e.getMethodName();
	}

	public static ByteArrayInputStream getModelDescription(File fmu)
			throws ZipException, IOException
	{
		ZipFile zipFile = null;
		try
		{
			zipFile = new ZipFile(fmu);

			ZipEntry entry = zipFile.getEntry("modelDescription.xml");

			if (entry != null)
			{
				ByteArrayInputStream stream = new ByteArrayInputStream(IOUtils.toByteArray(zipFile.getInputStream(entry)));
				return stream;

			}
		} finally
		{
			if (zipFile != null)
			{
				zipFile.close();
			}
		}
		return null;
	}

	static NodeList lookup(Object doc, XPath xpath, String expression)
			throws XPathExpressionException
	{
		XPathExpression expr = xpath.compile(expression);

		final NodeList list = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

		return list;

	}

	static Node lookupSingle(Object doc, XPath xpath, String expression)
			throws XPathExpressionException
	{
		NodeList list = lookup(doc, xpath, expression);
		if (list != null)
		{
			return list.item(0);
		}
		return null;
	}

	static Node lookupSingle(Object doc, XPath xpath, String... expression)
			throws XPathExpressionException
	{

		Object current = doc;
		for (String exp : expression)
		{
			current = lookupSingle(current, xpath, exp);

			if (current == null)
			{
				return null;
			}

		}

		return (Node) current;
	}

	@Test
	public void testExportFmu() throws AbortException, IOException,
			InterruptedException, SAXException, ParserConfigurationException,
			XPathExpressionException
	{
		String output = "target/" + this.getClass().getSimpleName() + "/"
				+ getCurrentClassAndMethodNames() + "/";

		FileUtils.copyDirectory(new File("src/test/resources/model"), new File(output));

		Main.main(new String[] { "-name", "wt2", "-export", "tool", "-root",
				output, "-output", output, "-v" });

		File outputZip = new File(output + "/wt2.fmu");

		List<String> files = Collections.synchronizedList(new Vector<>());
		try (ZipFile zipFile = new ZipFile(outputZip);)
		{
			zipFile.stream().map(ZipEntry::getName).collect(Collectors.toCollection(() -> files));
		}

		String[] expectedFiles = new String[] {

		"binaries/darwin64/wt2.dylib", "binaries/linux32/wt2.so",
				"binaries/linux64/wt2.so", "binaries/win32/wt2.dll",
				"binaries/win64/wt2.dll", "modelDescription.xml",
				"resources/config.txt",
				"resources/fmi-interpreter-jar-with-dependencies.jar",
				"resources/model/Controller.vdmrt",
				"resources/model/HardwareInterface.vdmrt",
				"resources/model/LevelSensor.vdmrt",
				"resources/model/lib/Fmi.vdmrt",
				"resources/model/lib/IO.vdmrt", "resources/model/System.vdmrt",
				"resources/model/ValveActuator.vdmrt",
				"resources/model/World.vdmrt", "resources/modelDescription.xml"

		};

		for (String string : expectedFiles)
		{
			Assert.assertTrue("Missing: " + string, files.contains(string));
		}
	}

	@Test
	public void testExportFmuNoName() throws AbortException, IOException,
			InterruptedException, SAXException, ParserConfigurationException,
			XPathExpressionException
	{
		String output = "target/" + this.getClass().getSimpleName() + "/"
				+ getCurrentClassAndMethodNames() + "/";

		FileUtils.copyDirectory(new File("src/test/resources/model_no_name"), new File(output));

		Main.main(new String[] { "-name", "wt2", "-export", "tool", "-root",
				output, "-output", output, "-v" });

		File outputZip = new File(output + "/wt2.fmu");

		List<String> files = Collections.synchronizedList(new Vector<>());
		try (ZipFile zipFile = new ZipFile(outputZip);)
		{
			zipFile.stream().map(ZipEntry::getName).collect(Collectors.toCollection(() -> files));
		}

		String[] expectedFiles = new String[] {

		"binaries/darwin64/wt2.dylib", "binaries/linux32/wt2.so",
				"binaries/linux64/wt2.so", "binaries/win32/wt2.dll",
				"binaries/win64/wt2.dll", "modelDescription.xml",
				"resources/config.txt",
				"resources/fmi-interpreter-jar-with-dependencies.jar",
				"resources/model/Controller.vdmrt",
				"resources/model/HardwareInterface.vdmrt",
				"resources/model/LevelSensor.vdmrt",
				"resources/model/lib/Fmi.vdmrt",
				"resources/model/lib/IO.vdmrt", "resources/model/System.vdmrt",
				"resources/model/ValveActuator.vdmrt",
				"resources/model/World.vdmrt", "resources/modelDescription.xml"

		};

		for (String string : expectedFiles)
		{
			Assert.assertTrue("Missing: " + string, files.contains(string));
		}
	}

	@Test
	public void testExportAllTypes() throws AbortException, IOException,
			InterruptedException, SAXException, ParserConfigurationException,
			DOMException, XPathExpressionException
	{
		String output = "target/" + this.getClass().getSimpleName() + "/"
				+ getCurrentClassAndMethodNames() + "/";

		FileUtils.copyDirectory(new File("src/test/resources/modelAllTypes"), new File(output));

		Main.main(new String[] { "-name", "wt2", "-export", "tool", "-root",
				output, "-output", output, "-v" });

		File outputZip = new File(output + "/wt2.fmu");

		List<String> files = Collections.synchronizedList(new Vector<>());
		try (ZipFile zipFile = new ZipFile(outputZip);)
		{
			zipFile.stream().map(ZipEntry::getName).collect(Collectors.toCollection(() -> files));
		}

		String[] expectedFiles = new String[] {

		"binaries/darwin64/wt2.dylib", "binaries/linux32/wt2.so",
				"binaries/linux64/wt2.so", "binaries/win32/wt2.dll",
				"binaries/win64/wt2.dll", "modelDescription.xml",
				"resources/config.txt",
				"resources/fmi-interpreter-jar-with-dependencies.jar",
				"resources/model/HardwareInterface.vdmrt",
				"resources/model/lib/Fmi.vdmrt",
				"resources/model/System.vdmrt", "resources/model/World.vdmrt",
				"resources/modelDescription.xml"

		};

		for (String string : expectedFiles)
		{
			Assert.assertTrue("Missing: " + string, files.contains(string));
		}

		DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
		Document doc = docBuilderFactory.newDocumentBuilder().parse(getModelDescription(outputZip));

		XPathFactory xPathfactory = XPathFactory.newInstance();
		XPath xpath = xPathfactory.newXPath();

		// parameters
		Assert.assertEquals("1.0", getStartValue(doc, xpath, "r"));
		Assert.assertEquals("0.0", getStartValue(doc, xpath, "r_default"));

		Assert.assertEquals("1", getStartValue(doc, xpath, "i"));
		Assert.assertEquals("0", getStartValue(doc, xpath, "i_default"));

		Assert.assertEquals("true", getStartValue(doc, xpath, "b"));
		Assert.assertEquals("false", getStartValue(doc, xpath, "b_default"));

		Assert.assertEquals("some value", getStartValue(doc, xpath, "s"));
		Assert.assertEquals("", getStartValue(doc, xpath, "s_empty"));
		Assert.assertEquals("", getStartValue(doc, xpath, "s_default"));

		// inputs
		Assert.assertEquals("1.0", getStartValue(doc, xpath, "in_r"));
		Assert.assertEquals("0.0", getStartValue(doc, xpath, "in_r_default"));

		Assert.assertEquals("1", getStartValue(doc, xpath, "in_i"));
		Assert.assertEquals("0", getStartValue(doc, xpath, "in_i_default"));

		Assert.assertEquals("true", getStartValue(doc, xpath, "in_b"));
		Assert.assertEquals("false", getStartValue(doc, xpath, "in_b_default"));

		Assert.assertEquals("some value", getStartValue(doc, xpath, "in_s"));
		Assert.assertEquals("", getStartValue(doc, xpath, "in_s_empty"));
		Assert.assertEquals("", getStartValue(doc, xpath, "in_s_default"));

		// outputs
		Assert.assertEquals("1.0", getStartValue(doc, xpath, "out_r"));
		Assert.assertEquals("0.0", getStartValue(doc, xpath, "out_r_default"));

		Assert.assertEquals("1", getStartValue(doc, xpath, "out_i"));
		Assert.assertEquals("0", getStartValue(doc, xpath, "out_i_default"));

		Assert.assertEquals("true", getStartValue(doc, xpath, "out_b"));
		Assert.assertEquals("false", getStartValue(doc, xpath, "out_b_default"));

		Assert.assertEquals("some value", getStartValue(doc, xpath, "out_s"));
		Assert.assertEquals("", getStartValue(doc, xpath, "out_s_empty"));
		Assert.assertEquals("", getStartValue(doc, xpath, "out_s_default"));
	}

	@Test
	public void testNoWorldRunExportFmu() throws AbortException, IOException,
			InterruptedException, SAXException, ParserConfigurationException,
			XPathExpressionException
	{
		String output = "target/" + this.getClass().getSimpleName() + "/"
				+ getCurrentClassAndMethodNames() + "/";

		FileUtils.copyDirectory(new File("src/test/resources/missingWorldRun"), new File(output));

		Main.main(new String[] { "-name", "wt2", "-export", "tool", "-root",
				output, "-output", output, "-v" });

		File outputZip = new File(output + "/wt2.fmu");

		Assert.assertFalse("Did not expect an FMU", outputZip.exists());
	}

	String getStartValue(Document doc, XPath xpath, String name)
			throws DOMException, XPathExpressionException
	{
		String initial = lookupSingle(doc, xpath, String.format("/fmiModelDescription/ModelVariables/ScalarVariable[@name='%s']", name), "Real[1] | Boolean[1] | String[1] | Integer[1] | Enumeration[1]", "@start").getNodeValue();
		return initial;
	}

	Node getStart(Document doc, XPath xpath, String name) throws DOMException,
			XPathExpressionException
	{
		return lookupSingle(doc, xpath, String.format("/fmiModelDescription/ModelVariables/ScalarVariable[@name='%s']", name), "Real[1] | Boolean[1] | String[1] | Integer[1] | Enumeration[1]", "@start");
	}
}
