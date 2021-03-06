/*
 * #%~
 * Fmu import exporter
 * %%
 * Copyright (C) 2015 - 2017 Overture
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #~%
 */
package org.overturetool.fmi.imports;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.overture.ast.definitions.AExplicitOperationDefinition;
import org.overture.ast.definitions.AInstanceVariableDefinition;
import org.overture.ast.definitions.ASystemClassDefinition;
import org.overture.ast.definitions.PDefinition;
import org.overture.ast.definitions.SClassDefinition;
import org.overture.ast.types.PType;
import org.overture.fmi.annotation.FmuAnnotation;
import org.overture.fmi.ide.fmuexport.xml.NamedNodeMapIterator;
import org.overture.fmi.ide.fmuexport.xml.NodeIterator;
import org.overturetool.fmi.AbortException;
import org.overturetool.fmi.IProject;
import org.overturetool.fmi.util.Tracability;
import org.overturetool.fmi.util.VdmAnnotationProcesser;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class ImportModelDescriptionProcesser
{
	private static final String HARDWARE_INTERFACE = "HardwareInterface";

	public enum Types
	{
		Boolean, Real, Integer, String, Enumeration;

		public static Types valueOfIgnorecase(String value)
		{
			for (Types t : values())
			{
				if (t.name().equalsIgnoreCase(value))
				{
					return t;
				}
			}
			return null;
		}
	}

	public enum Causality
	{
		Parameter, CalculatedParameter, Input, Output, Local, Independent;
		public static Causality valueOfIgnorecase(String value)
		{
			for (Causality c : values())
			{
				if (c.name().equalsIgnoreCase(value))
				{
					return c;
				}
			}
			return null;
		}
	}

	public enum Variability
	{
		Constant, Fixed, Tunable, Discrete, Continuous;
		public static Variability valueOfIgnorecase(String value)
		{
			for (Variability v : values())
			{
				if (v.name().equalsIgnoreCase(value))
				{
					return v;
				}
			}
			return null;
		}
	}

	public enum Initial
	{
		Exact, Approx, Calculated;
		public static Initial valueOfIgnorecase(String value)
		{
			for (Initial i : values())
			{
				if (i.name().equalsIgnoreCase(value))
				{
					return i;
				}
			}
			return null;
		}
	}

	public static class Type
	{
		public Types type;
		public Object start;

		@Override
		public String toString()
		{
			return type + (start != null ? " " + start : "");
		}
	}

	public static class ScalarVariable
	{
		public enum DependencyKind
		{
			Dependent, Constant, Fixed, Tunable, Discrete, Unspecified
		}

		public String name;
		public long valueReference;
		public String description;
		public Causality causality;
		public Variability variability;
		public Initial initial;
		public Type type;

		public Type getType()
		{
			return type;
		}

		public String getName()
		{
			return name;
		}

		public Long getValueReference()
		{
			return new Long(valueReference);
		}

		@Override
		public String toString()
		{
			return getName();
		}
	}

	private static final boolean DEBUG = false;
	final PrintStream out;
	final PrintStream err;

	public ImportModelDescriptionProcesser(PrintStream out, PrintStream err)
	{
		this.out = out;
		this.err = err;
	}

	public void importFromXml(IProject project, File file) throws SAXException,
			IOException, ParserConfigurationException, AbortException
	{

		out.println("\n---------------------------------------");
		out.println("|         Model Description Import       |");
		out.println("---------------------------------------");
		out.println("Starting FMU import for project: '" + project.getName()
				+ "'");

		String hash = Tracability.calculateGitHash(file);
		out.println(file.getName() + "\t" + hash);
		if (project.typeCheck())
		{
			try
			{
				out.println("Collecting existing annotations...");

				Map<PDefinition, FmuAnnotation> annotations = new VdmAnnotationProcesser().collectAnnotatedDefinitions(project, out, err);
				out.println("\t Found " + annotations.size()
						+ " annotations in model");

				out.println("Parsing ModelDescription.xml file...");
				// Document document;
				DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
				// validateAgainstXSD(new StreamSource(xmlInputStream), schemaSource);

				// xmlInputStream.reset();
				Document doc = docBuilderFactory.newDocumentBuilder().parse(file);

				XPathFactory xPathfactory = XPathFactory.newInstance();
				XPath xpath = xPathfactory.newXPath();

				List<ScalarVariable> vars = new Vector<ScalarVariable>();

				for (Node n : new NodeIterator(lookup(doc, xpath, "fmiModelDescription/ModelVariables/ScalarVariable")))
				{
					ScalarVariable sc = new ScalarVariable();
					// indexMap.put(++index, sc);

					NamedNodeMap attributes = n.getAttributes();
					sc.name = attributes.getNamedItem("name").getNodeValue();
					sc.valueReference = Long.parseLong(attributes.getNamedItem("valueReference").getNodeValue());

					// optional
					sc.causality = getAttribute(Causality.class, attributes, "causality");
					// sc.variability = getAttribute(Variability.class, attributes, "variability");
					sc.initial = getAttribute(Initial.class, attributes, "initial");
					// sc.description = getNodeValue(attributes, "description", "");

					Node child = lookupSingle(n, xpath, "Real[1] | Boolean[1] | String[1] | Integer[1] | Enumeration[1]");

					if (child == null)
					{
						String msg = "Missing type for: " + sc.name;
						err.println(msg);
						throw new AbortException(msg);
					}

					sc.type = new Type();
					sc.type.type = Types.valueOfIgnorecase(child.getNodeName());

					if (child.getAttributes() != null)
					{
						Node startAtt = child.getAttributes().getNamedItem("start");
						if (startAtt != null)
						{
							switch (sc.type.type)
							{
								case Boolean:
									sc.type.start = Boolean.valueOf(startAtt.getNodeValue());
									break;
								case Integer:
									sc.type.start = Integer.valueOf(startAtt.getNodeValue());
									break;
								case Real:
									sc.type.start = Double.valueOf(startAtt.getNodeValue());
									break;
								case String:
								default:
									sc.type.start = startAtt.getNodeValue();
									break;

							}

						}
					}

					vars.add(sc);
				}
				out.println("\t Found " + vars.size()
						+ " scalar variables in '" + file.getName() + "'");

				if (validate(annotations, vars, err))
				{
					checkAndCreateStructure(project);
					out.println("Importing...");
					out.println("");
					List<ScalarVariable> filter = filter(annotations, vars, out, err);
					// VdmTypeCheckerUi.typeCheck(shell, project);
					project.typeCheck();
					updateHardwareInterface(project, filter, out, hash, file.getName().replace(" ", "%20"));
					out.println("");
					out.println("Import comepleted.");
				} else
				{
					err.println("Aborting");
					return;
				}

			} catch (Exception e)
			{
				throw new AbortException(e.getMessage(),e);
			}
		} else
		{
			String msg = "Aborting VDM model does not type check";
			err.println(msg);
			throw new AbortException(msg);
		}
	}

	private List<ScalarVariable> filter(
			Map<PDefinition, FmuAnnotation> annotations,
			List<ScalarVariable> vars, PrintStream out2, PrintStream err2)
	{
		List<ScalarVariable> skipVars = new Vector<ImportModelDescriptionProcesser.ScalarVariable>();

		for (Entry<PDefinition, FmuAnnotation> entry : annotations.entrySet())
		{
			for (ScalarVariable sv : vars)
			{
				if (sv.getName().equals(entry.getValue().name))
				{
					skipVars.add(sv);

					PType type = entry.getKey().getType();
					out.println("Skipping import of '" + sv.name + "'");
					if (!type.toString().equals(toVdmType(sv.type)))
					{
						err.println("WARNING: Skipping "
								+ sv.name
								+ " note that the defined type does not match importing. Defined: '"
								+ type + "' Importing: '" + toVdmType(sv.type)
								+ "' at " + type.getLocation());
					}
				}
			}
		}

		vars.removeAll(skipVars);
		return vars;
	}

	private void updateHardwareInterface(IProject project,
			List<ScalarVariable> vars, PrintStream out, Object hash,
			Object importFileName) throws IOException
	{
		out.println("");
		SClassDefinition hwi = getClassByName(project.getClasses(), HARDWARE_INTERFACE);

		File file = hwi.getLocation().getFile();

		String data = FileUtils.readFileToString(file, Charset.forName("UTF-8"));

		int endOffset = hwi.getName().getLocation().getEndOffset();

		StringBuilder sb = new StringBuilder(data);

		StringBuilder sbValues = new StringBuilder();
		StringBuilder sbOutputs = new StringBuilder();
		StringBuilder sbInputs = new StringBuilder();

		String valueTemplate = "\t-- @ interface: type = parameter, name=\"%s\";\n\tpublic %s : %s = %s;\n";
		String inputTemplate = "\t-- @ interface: type = input, name=\"%s\";\n\tpublic %s : %s := %s;\n";
		String outputTemplate = "\t-- @ interface: type = output, name=\"%s\";\n\tpublic %s : %s := %s;\n";

		for (ScalarVariable scalarVariable : vars)
		{
			out.println("Importing Scalar Variable '" + scalarVariable.name
					+ "'");
			String name = toVdm(scalarVariable.name);
			String type = toVdmType(scalarVariable.type);
			String value = getValueOrDefault(scalarVariable.type);
			switch (scalarVariable.causality)
			{
				case CalculatedParameter:
					out.println("\tSkipping CalculatedParameter");
					break;
				case Independent:
					out.println("\tSkipping Independent");
					break;
				case Input:
					sbInputs.append(String.format(inputTemplate, scalarVariable.name, name, type, value));
					break;
				case Local:
					out.println("\tSkipping local");
					break;
				case Output:
					sbOutputs.append(String.format(outputTemplate, scalarVariable.name, name, type, value));
					break;
				case Parameter:
					sbValues.append(String.format(valueTemplate, scalarVariable.name, name, type, value));
					break;
				default:
					break;
			}
		}

		if (sbOutputs.length() > 0)
		{
			sb.insert(endOffset, "instance variables\n" + sbOutputs + "\n\n");
		}
		if (sbInputs.length() > 0)
		{
			sb.insert(endOffset, "instance variables\n" + sbInputs + "\n\n");
		}
		if (sbValues.length() > 0)
		{
			sb.insert(endOffset, "\nvalues\n" + sbValues + "\n\n");
		}

		// insert at beginning but after all other inserts to preserve index offset
		if (project.isTracabilityEnabled())
		{
			sb.insert(0, String.format("--##\tIMPORT\t%s\t%s\t%s\t%s\t%s\n", hash, importFileName, Tracability.getCurrentTimeStamp(), "FMI-ModelDescription", Tracability.getToolId()));
		}
		FileUtils.write(file, sb, Charset.forName("UTF-8"));
	}

	private String getValueOrDefault(Type type)
	{

		switch (type.type)
		{
			case Boolean:
			{
				String ret = null;
				if (type.start != null)
				{
					if (type.start instanceof Boolean)
					{
						ret = type.start.toString();
					}
					if (type.start instanceof Integer)
					{
						ret = (Integer) type.start == 0 ? "false" : "true";
					}
				} else
				{
					ret = "false";
				}
				return "new BoolPort(" + ret + ")";
			}
			case Enumeration:
				break;
			case Integer:
			case Real:
			{
				String ret = null;
				if (type.start != null)
				{
					ret = type.start.toString();
				} else
				{
					return "0";
				}

				if (type.type == Types.Real)
				{
					return "new RealPort(" + ret + ")";
				} else
				{
					return "new IntPort(" + ret + ")";
				}
			}
			case String:
			{

				String ret = null;
				if (type.start != null)
				{
					ret = "\"" + type.start.toString() + "\"";
				} else
				{
					ret = "";
				}
				return "new StringPort(" + ret + ")";
			}
			default:
				break;
		}
		return null;
	}

	private String toVdmType(Type type)
	{
		switch (type.type)
		{
			case Boolean:
				return "BoolPort";
			case Enumeration:
				break;
			case Integer:
				return "IntPort";
			case Real:
				return "RealPort";
			case String:
				return "StringPort";
			default:
				break;
		}
		return null;
	}

	private String toVdm(String name)
	{
		return name.replaceAll("[^A-Za-z0-9()\\[\\]]", "");
	}


	void copyVdmSourceTemplateToProject(IProject project, String classPathPath,
			String projectPath) throws IOException
	{
		InputStream is = this.getClass().getClassLoader().getResourceAsStream(classPathPath);
		project.createSpecFileProjectRelative(projectPath, is);
	}

	private void checkAndCreateStructure(IProject project)
			throws AbortException, IOException
	{
		out.println("Checking class 'World'");
		checkCreateWorld(project);

		out.println("Checking for FMI library");
		SClassDefinition port = getClassByName(project.getClasses(), "Port");
		for (String pName : new String[] { "RealPort", "IntPort", "BoolPort",
				"StringPort" })
		{
			if (getClassByName(project.getClasses(), pName) == null)
			{
				port = null;
			}
		}
		if (port == null)
		{
			copyVdmSourceTemplateToProject(project, "fmi/Fmi.vdmrt", "lib/Fmi.vdmrt");
		}

		out.println("Checking class '" + HARDWARE_INTERFACE + "'");
		SClassDefinition hwi = getClassByName(project.getClasses(), HARDWARE_INTERFACE);
		if (hwi == null)
		{
			copyVdmSourceTemplateToProject(project, "templates/HardwareInterface.vdmrt", "HardwareInterface.vdmrt");
		}

		out.println("Checking the system");
		checkCreateSystem(project);
	}

	public void checkCreateSystem(IProject project) throws AbortException,
			IOException
	{
		SClassDefinition systemClass = null;
		for (SClassDefinition def : project.getClasses())
		{
			if (def instanceof ASystemClassDefinition)
			{
				systemClass = def;
			}
		}

		if (systemClass == null)
		{
			copyVdmSourceTemplateToProject(project, "templates/System.vdmrt", "System.vdmrt");
		} else
		{
			boolean found = false;
			for (PDefinition def : systemClass.getDefinitions())
			{
				if ("hwi".equals(def.getName().getName())
						&& def instanceof AInstanceVariableDefinition)
				{
					found = true;
					break;
				}
			}

			if (!found)
			{
				err.println("Missing 'HardwareDefinition' instance variable in '"
						+ systemClass.getName().getName() + "'");
				err.println("\t Add this block to the system class:");
				err.println("");
				err.println("\t instance variables");
				err.println("\t public static hwi: HardwareInterface := new HardwareInterface();");
				throw new AbortException("Missing hardware definition in system");
			}
		}
	}

	public void checkCreateWorld(IProject project) throws AbortException,
			IOException
	{
		SClassDefinition worldClass = getClassByName(project.getClasses(), "World");
		if (worldClass == null)
		{
			copyVdmSourceTemplateToProject(project, "templates/World.vdmrt", "World.vdmrt");
		} else
		{
			boolean found = false;
			for (PDefinition def : worldClass.getDefinitions())
			{
				if ("run".equals(def.getName().getName())
						&& def instanceof AExplicitOperationDefinition)
				{
					found = true;
					break;
				}
			}

			if (!found)
			{
				err.println("Missing public explicit 'run' operation in class World");
				throw new AbortException("Missing world run operation");
			}
		}
	}

	SClassDefinition getClassByName(List<? extends SClassDefinition> classList,
			String name)
	{
		for (SClassDefinition def : classList)
		{
			if (name.equals(def.getName().getName()))
			{
				return def;
			}
		}
		return null;
	}

	private boolean validate(Map<PDefinition, FmuAnnotation> annotations,
			List<ScalarVariable> vars, PrintStream err)
	{
		List<Entry<PDefinition, FmuAnnotation>> additionalInputs = new Vector<Map.Entry<PDefinition, FmuAnnotation>>();
		boolean abort = false;
		for (Entry<PDefinition, FmuAnnotation> entry : annotations.entrySet())
		{
			if ("input".equals(entry.getValue().type)
					|| "output".equals(entry.getValue().type))
			{
				if (!HARDWARE_INTERFACE.equals(entry.getKey().getClassDefinition().getName().getName()))
				{
					err.println("Model annotation of input/output outside class '"
							+ HARDWARE_INTERFACE
							+ "' not supported. "
							+ entry.getKey().getLocation());
					abort = true;
				}
			}

			if ("input".equals(entry.getValue().type))
			{
				boolean found = false;
				for (ScalarVariable sv : vars)
				{
					if (sv.name.equals(entry.getValue().name))
					{
						found = true;
						break;
					}
				}

				if (!found)
				{
					additionalInputs.add(entry);
				}

			}
		}

		if (!additionalInputs.isEmpty())
		{
			err.println("Model not compatible with selected model description. Too many inputs defined.");
			for (Entry<PDefinition, FmuAnnotation> entry : additionalInputs)
			{
				err.println("\tInput '"
						+ entry.getValue().name
						+ "' not defined in model description. Source location: "
						+ entry.getKey().getLocation());
			}
		}

		return !abort;
	}

	// //////// XML stuff

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

	static NodeList lookup(Object doc, XPath xpath, String expression)
			throws XPathExpressionException
	{
		XPathExpression expr = xpath.compile(expression);

		if (DEBUG)
		{
			System.out.println("Starting from: " + formateNodeWithAtt(doc));
		}
		final NodeList list = (NodeList) expr.evaluate(doc, XPathConstants.NODESET);

		if (DEBUG)
		{
			System.out.print("\tFound: ");
		}
		boolean first = true;
		for (Node n : new NodeIterator(list))
		{
			if (DEBUG)
			{
				System.out.println((!first ? "\t       " : "")
						+ formateNodeWithAtt(n));
			}
			first = false;
		}
		if (first)
		{
			if (DEBUG)
			{
				System.out.println("none");
			}
		}
		return list;

	}

	public static String formateNodeWithAtt(Object o)
	{
		if (o instanceof Document)
		{
			return "Root document";
		} else if (o instanceof Node)
		{
			Node node = (Node) o;

			String tmp = "";
			tmp = node.getLocalName();
			if (node.hasAttributes())
			{
				for (Node att : new NamedNodeMapIterator(node.getAttributes()))
				{
					tmp += " " + att + ", ";
				}
			}
			return tmp;
		}
		return o.toString();
	}

	private <T extends Enum<T>> T getAttribute(Class<T> en,
			NamedNodeMap attributes, String name)
	{
		Node att = attributes.getNamedItem(name);
		if (att != null)
		{
			return (T) Enum.valueOf(en, StringUtils.capitalize(att.getNodeValue()));
		}
		return null;
	}

}
