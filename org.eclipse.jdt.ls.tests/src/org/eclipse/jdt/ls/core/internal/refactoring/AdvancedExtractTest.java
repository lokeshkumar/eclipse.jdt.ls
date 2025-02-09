/*******************************************************************************
 * Copyright (c) 2019 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.refactoring;

import static org.mockito.Mockito.when;

import java.util.Hashtable;
import java.util.List;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.ls.core.internal.JavaCodeActionKind;
import org.eclipse.jdt.ls.core.internal.correction.AbstractSelectionTest;
import org.eclipse.jdt.ls.core.internal.correction.TestOptions;
import org.eclipse.jdt.ls.core.internal.handlers.CodeActionHandlerTest;
import org.eclipse.jdt.ls.core.internal.preferences.ClientPreferences;
import org.eclipse.jdt.ls.core.internal.text.correction.ExtractProposalUtility;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class AdvancedExtractTest extends AbstractSelectionTest {
	private IJavaProject fJProject1;
	private IPackageFragmentRoot fSourceFolder;

	@Before
	public void setup() throws Exception {
		fJProject1 = newEmptyProject();
		Hashtable<String, String> options = TestOptions.getDefaultOptions();

		fJProject1.setOptions(options);
		fSourceFolder = fJProject1.getPackageFragmentRoot(fJProject1.getProject().getFolder("src"));
	}

	@Override
	protected ClientPreferences initPreferenceManager(boolean supportClassFileContents) {
		ClientPreferences clientPreferences = super.initPreferenceManager(supportClassFileContents);
		when(clientPreferences.isAdvancedExtractRefactoringSupported()).thenReturn(true);
		return clientPreferences;
	}

	@Test
	public void testExtractVariable() throws Exception {
		IPackageFragment pack1 = fSourceFolder.createPackageFragment("test1", false, null);

		StringBuilder buf = new StringBuilder();
		buf.append("package test1;\n");
		buf.append("class A{\n");
		buf.append("	void m(int i){\n");
		buf.append("		int x= /*]*/0/*[*/;\n");
		buf.append("	}\n");
		buf.append("}\n");

		ICompilationUnit cu = pack1.createCompilationUnit("E.java", buf.toString(), false, null);
		Range selection = getRange(cu, null);
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu, selection);
		Assert.assertEquals(5, codeActions.size());
		Command extractConstantCommand = CodeActionHandlerTest.getCommand(codeActions.get(0));
		Assert.assertNotNull(extractConstantCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractConstantCommand.getCommand());
		Assert.assertNotNull(extractConstantCommand.getArguments());
		Assert.assertEquals(2, extractConstantCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_CONSTANT_COMMAND, extractConstantCommand.getArguments().get(0));

		Command extractFieldCommand = CodeActionHandlerTest.getCommand(codeActions.get(1));
		Assert.assertNotNull(extractFieldCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractFieldCommand.getCommand());
		Assert.assertNotNull(extractFieldCommand.getArguments());
		Assert.assertEquals(3, extractFieldCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_FIELD_COMMAND, extractFieldCommand.getArguments().get(0));

		Command extractMethodCommand = CodeActionHandlerTest.getCommand(codeActions.get(2));
		Assert.assertNotNull(extractMethodCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractMethodCommand.getCommand());
		Assert.assertNotNull(extractMethodCommand.getArguments());
		Assert.assertEquals(2, extractMethodCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_METHOD_COMMAND, extractMethodCommand.getArguments().get(0));

		Command extractVariableAllCommand = CodeActionHandlerTest.getCommand(codeActions.get(3));
		Assert.assertNotNull(extractVariableAllCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractVariableAllCommand.getCommand());
		Assert.assertNotNull(extractVariableAllCommand.getArguments());
		Assert.assertEquals(2, extractVariableAllCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_VARIABLE_ALL_OCCURRENCE_COMMAND, extractVariableAllCommand.getArguments().get(0));

		Command extractVariableCommand = CodeActionHandlerTest.getCommand(codeActions.get(4));
		Assert.assertNotNull(extractVariableCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractVariableCommand.getCommand());
		Assert.assertNotNull(extractVariableCommand.getArguments());
		Assert.assertEquals(2, extractVariableCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_VARIABLE_COMMAND, extractVariableCommand.getArguments().get(0));
	}

	@Test
	public void testExtractMethod() throws Exception {
		IPackageFragment pack1 = fSourceFolder.createPackageFragment("test1", false, null);

		StringBuilder buf = new StringBuilder();
		buf.append("package test1;\n");
		buf.append("\n");
		buf.append("public class E {\n");
		buf.append("    public int foo(boolean b1, boolean b2) {\n");
		buf.append("        int n = 0;\n");
		buf.append("        int i = 0;\n");
		buf.append("        /*[*/\n");
		buf.append("        if (b1)\n");
		buf.append("            i = 1;\n");
		buf.append("        if (b2)\n");
		buf.append("            n = n + i;\n");
		buf.append("        /*]*/\n");
		buf.append("        return n;\n");
		buf.append("    }\n");
		buf.append("}\n");

		ICompilationUnit cu = pack1.createCompilationUnit("E.java", buf.toString(), false, null);
		Range selection = getRange(cu, null);
		List<Either<Command, CodeAction>> codeActions = evaluateCodeActions(cu, selection);
		Assert.assertNotNull(codeActions);
		Either<Command, CodeAction> extractMethodAction = CodeActionHandlerTest.findAction(codeActions, JavaCodeActionKind.REFACTOR_EXTRACT_METHOD);
		Assert.assertNotNull(extractMethodAction);
		Command extractMethodCommand = CodeActionHandlerTest.getCommand(extractMethodAction);
		Assert.assertNotNull(extractMethodCommand);
		Assert.assertEquals(ExtractProposalUtility.APPLY_REFACTORING_COMMAND_ID, extractMethodCommand.getCommand());
		Assert.assertNotNull(extractMethodCommand.getArguments());
		Assert.assertEquals(2, extractMethodCommand.getArguments().size());
		Assert.assertEquals(ExtractProposalUtility.EXTRACT_METHOD_COMMAND, extractMethodCommand.getArguments().get(0));
	}
}
