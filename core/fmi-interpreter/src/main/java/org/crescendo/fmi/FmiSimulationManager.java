/*
 * #%~
 * Fmi interface for the Crescendo Interpreter
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
package org.crescendo.fmi;

import java.util.List;
import java.util.Vector;

import org.apache.commons.lang3.StringUtils;
import org.destecs.core.vdmlink.StringPair;
import org.destecs.protocol.exceptions.RemoteSimulationException;
import org.destecs.vdm.SimulationManager;
import org.destecs.vdm.utility.SeqValueInfo;
import org.destecs.vdm.utility.VDMClassHelper;
import org.destecs.vdm.utility.ValueInfo;
import org.overture.ast.definitions.AValueDefinition;
import org.overture.ast.definitions.PDefinition;
import org.overture.ast.definitions.SClassDefinition;
import org.overture.ast.expressions.ANewExp;
import org.overture.ast.expressions.PExp;
import org.overture.interpreter.runtime.Interpreter;
import org.overture.interpreter.runtime.ValueException;
import org.overture.interpreter.runtime.state.ASystemClassDefinitionRuntime;
import org.overture.interpreter.values.NameValuePairList;
import org.overture.interpreter.values.RealValue;
import org.overture.interpreter.values.UndefinedValue;
import org.overture.interpreter.values.Value;
import org.overture.parser.util.ParserUtil;
import org.overture.parser.util.ParserUtil.ParserResult;
import org.overture.typechecker.util.TypeCheckerUtil;
import org.overture.typechecker.util.TypeCheckerUtil.TypeCheckResult;

public class FmiSimulationManager extends SimulationManager
{

	/**
	 * @return The unique instance of this class.
	 */
	static public FmiSimulationManager getInstance()
	{
		if (null == _instance)
		{
			_instance = new FmiSimulationManager();
		}
		return (FmiSimulationManager) _instance;
	}

	/**
	 * FMI step method using basic named values
	 * 
	 * @param outputTime
	 *            the time to step until
	 * @param inputs
	 *            the inputs needed for the step
	 * @return a list of outputs from the step
	 * @throws RemoteSimulationException
	 *             thrown if an internal error occur
	 */
	public synchronized List<NamedValue> step(long outputTime,
			List<NamedValue> inputs) throws RemoteSimulationException
	{
		checkMainContext();

		for (NamedValue p : inputs)
		{
			setScalarValue(p.name, getValue(p.name), p.value);
		}

		doInternalStep(outputTime, null);// no events
		
		List<NamedValue> outputs = new Vector<NamedValue>();
		outputs.add(new NamedValue("time", new RealValue(nextSchedulableActionTime), -1));

		for (String key : links.getOutputs().keySet())
		{
			try
			{
				NamedValue value = getSimpleOutput(key);
				if (value != null)
				{
					outputs.add(value);
				} else
				{
					throw new RemoteSimulationException("Faild to get output parameter, output not bound for: "
							+ key);
				}
			} catch (ValueException e)
			{
				logger.error(e.getMessage(),e);
				throw new RemoteSimulationException("Faild to get output parameter", e);
			}
		}

		return outputs;
	}

	/**
	 * unwrap seqvarinfo's basically removing the notion of these since they have the wrong behavior where they remove
	 * the transactional value holding them. This results in that they cannot be updated. This function avoids that by
	 * removing these special seqvalueinfos
	 * 
	 * @param value
	 *            the value to potential unwrap
	 * @return the unwrapped value
	 */
	ValueInfo unwrapSeqInfoValue(ValueInfo value)
	{
		if (value == null)
		{
			return null;
		} else if (value instanceof SeqValueInfo)
		{
			return new ValueInfo(value.name, value.classDef, ((SeqValueInfo) value).source, value.cpu);
		}
		return value;
	}

	/**
	 * changed the behavior of getValue to unwrap seqvalueinfo
	 */
	@Override
	protected ValueInfo getValue(String name) throws RemoteSimulationException
	{
		return unwrapSeqInfoValue(super.getValue(name));
	}

	/**
	 * gets a single NamedValue based on its name
	 * 
	 * @param name
	 * @return
	 * @throws ValueException
	 * @throws RemoteSimulationException
	 */
	private NamedValue getSimpleOutput(String name) throws ValueException,
			RemoteSimulationException
	{
		NameValuePairList list = ASystemClassDefinitionRuntime.getSystemMembers();
		if (list != null && links.getLinks().containsKey(name))
		{
			List<String> varName = links.getQualifiedName(name);

			Value value = VDMClassHelper.digForVariable(varName.subList(1, varName.size()), list).value;

			if (value.deref() instanceof UndefinedValue)
			{
				throw new RemoteSimulationException("Value: " + name
						+ " not initialized");
			}

			return new NamedValue(name, value, -1);

		}
		throw new RemoteSimulationException("Value: " + name + " not found");
	}

	/**
	 * Sets design parameters. All class definitions in the loaded model are searched and existing value definitions are
	 * Extracted. If their name match the parameter the LexRealToken value is updated by reflection (value is final)
	 * with the new value from the design parameter
	 * 
	 * @param parameter
	 *            A list of Maps containing (name,value) keys and {@code name->String}, {@code value->Double}
	 * @return false if any error occur else true
	 * @throws RemoteSimulationException
	 *             thrown if an internal error occur
	 */
	public Boolean setParameter(NamedValue parameter)
			throws RemoteSimulationException
	{
		try
		{
			boolean found = false;
			String parameterName = parameter.name;

			if (!links.getSharedDesignParameters().keySet().contains(parameterName))
			{
				logger.error("Tried to set unlinked shared design parameter: "
						+ parameterName);
				throw new RemoteSimulationException("Tried to set unlinked shared design parameter: "
						+ parameterName);
			}
			@SuppressWarnings("deprecation")
			StringPair vName = links.getBoundVariable(parameterName);

			for (SClassDefinition cd : controller.getInterpreter().getClasses())
			{
				if (!cd.getName().getName().equals(vName.instanceName))
				{
					// wrong class
					continue;
				}
				for (PDefinition def : cd.getDefinitions())
				{
					if (def instanceof AValueDefinition)
					{
						AValueDefinition vDef = (AValueDefinition) def;
						if (vDef.getPattern().toString().equals(vName.variableName)
								&& Interpreter.getInstance().getAssistantFactory().createPDefinitionAssistant().isValueDefinition(vDef))
						{
							found = true;
							ParserResult<PExp> res = ParserUtil.parseExpression(parameter.value.toString());

							if (res.errors.isEmpty())
							{
								if (vDef.getExpression() instanceof ANewExp)
								{
									ANewExp newExp = (ANewExp) vDef.getExpression();
									if (newExp.getArgs().isEmpty())
									{
										TypeCheckResult<PExp> tcRes = TypeCheckerUtil.typeCheckExpression(parameter.value.toString());
										if (tcRes.errors.isEmpty())
										{
											newExp.getArgs().add(tcRes.result);
										} else
											throw new RemoteSimulationException("Unable to parse parameter: "
													+ parameter);
									} else
									{
										newExp.getArgs().set(0, res.result);
									}
								}
								// vDef.setExpression(res.result);
							} else
							{
								throw new RemoteSimulationException("Unable to parse initial parameter expression: '"
										+ parameter.value
										+ "' "
										+ StringUtils.join(res.errors, ","));
							}
						}
					}

				}
				if (!found)
				{
					logger.error("Tried to set unlinked shared design parameter: "
							+ parameterName);
					throw new RemoteSimulationException("Tried to set unlinked shared design parameter: "
							+ parameterName);
				}
			}
		} catch (RemoteSimulationException e)
		{
			throw e;
		} catch (Exception e)
		{
			logger.error(e.getMessage(),e);
			if (e instanceof RemoteSimulationException)
			{
				throw (RemoteSimulationException) e;
			}
			throw new RemoteSimulationException("Internal error in set design parameters", e);
		}

		return true;
	}
}
