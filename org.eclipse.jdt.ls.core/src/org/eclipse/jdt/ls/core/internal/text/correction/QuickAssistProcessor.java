/*******************************************************************************
 * Copyright (c) 2000, 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Originally copied from org.eclipse.jdt.internal.ui.text.correction.QuickAssistProcessor;
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Sebastian Davids <sdavids@gmx.de> - Bug 37432 getInvertEqualsProposal
 *     Benjamin Muskalla <b.muskalla@gmx.net> - Bug 36350 convertToStringBufferPropsal
 *     Chris West (Faux) <eclipse@goeswhere.com> - [quick assist] "Use 'StringBuilder' for string concatenation" could fix existing misuses - https://bugs.eclipse.org/bugs/show_bug.cgi?id=282755
 *     Lukas Hanke <hanke@yatta.de> - Bug 241696 [quick fix] quickfix to iterate over a collection - https://bugs.eclipse.org/bugs/show_bug.cgi?id=241696
 *     Eugene Lucash <e.lucash@gmail.com> - [quick assist] Add key binding for Extract method Quick Assist - https://bugs.eclipse.org/424166
 *     Lukas Hanke <hanke@yatta.de> - Bug 430818 [1.8][quick fix] Quick fix for "for loop" is not shown for bare local variable/argument/field - https://bugs.eclipse.org/bugs/show_bug.cgi?id=430818
 *     Jeremie Bresson <dev@jmini.fr> - Bug 439912: [1.8][quick assist] Add quick assists to add and remove parentheses around single lambda parameter - https://bugs.eclipse.org/439912
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.text.correction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayCreation;
import org.eclipse.jdt.core.dom.ArrayInitializer;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.CreationReference;
import org.eclipse.jdt.core.dom.Dimension;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionMethodReference;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodReference;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeMethodReference;
import org.eclipse.jdt.core.dom.UnionType;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.manipulation.CleanUpOptionsCore;
import org.eclipse.jdt.core.manipulation.CodeStyleConfiguration;
import org.eclipse.jdt.internal.core.manipulation.StubUtility;
import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.GenericVisitor;
import org.eclipse.jdt.internal.corext.dom.ScopeAnalyzer;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.corext.fix.IProposableFix;
import org.eclipse.jdt.internal.corext.fix.LinkedProposalModelCore;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.ui.text.correction.IProblemLocationCore;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.corext.fix.LambdaExpressionsCleanUp;
import org.eclipse.jdt.ls.core.internal.corext.fix.LambdaExpressionsFix;
import org.eclipse.jdt.ls.core.internal.corrections.CorrectionMessages;
import org.eclipse.jdt.ls.core.internal.corrections.IInvocationContext;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.ASTRewriteCorrectionProposal;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.AssignToVariableAssistProposal;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.CUCorrectionProposal;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.FixCorrectionProposal;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.IProposalRelevance;
import org.eclipse.jdt.ls.core.internal.corrections.proposals.TypeChangeCorrectionProposal;
import org.eclipse.jdt.ls.core.internal.preferences.PreferenceManager;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;

/**
  */
public class QuickAssistProcessor {

	public static final String SPLIT_JOIN_VARIABLE_DECLARATION_ID = "org.eclipse.jdt.ls.correction.splitJoinVariableDeclaration.assist"; //$NON-NLS-1$
	public static final String CONVERT_FOR_LOOP_ID = "org.eclipse.jdt.ls.correction.convertForLoop.assist"; //$NON-NLS-1$
	public static final String ASSIGN_TO_LOCAL_ID = "org.eclipse.jdt.ls.correction.assignToLocal.assist"; //$NON-NLS-1$
	public static final String ASSIGN_TO_FIELD_ID = "org.eclipse.jdt.ls.correction.assignToField.assist"; //$NON-NLS-1$
	public static final String ASSIGN_PARAM_TO_FIELD_ID = "org.eclipse.jdt.ls.correction.assignParamToField.assist"; //$NON-NLS-1$
	public static final String ASSIGN_ALL_PARAMS_TO_NEW_FIELDS_ID = "org.eclipse.jdt.ls.correction.assignAllParamsToNewFields.assist"; //$NON-NLS-1$
	public static final String ADD_BLOCK_ID = "org.eclipse.jdt.ls.correction.addBlock.assist"; //$NON-NLS-1$
	public static final String EXTRACT_LOCAL_ID = "org.eclipse.jdt.ls.correction.extractLocal.assist"; //$NON-NLS-1$
	public static final String EXTRACT_LOCAL_NOT_REPLACE_ID = "org.eclipse.jdt.ls.correction.extractLocalNotReplaceOccurrences.assist"; //$NON-NLS-1$
	public static final String EXTRACT_CONSTANT_ID = "org.eclipse.jdt.ls.correction.extractConstant.assist"; //$NON-NLS-1$
	public static final String INLINE_LOCAL_ID = "org.eclipse.jdt.ls.correction.inlineLocal.assist"; //$NON-NLS-1$
	public static final String CONVERT_LOCAL_TO_FIELD_ID = "org.eclipse.jdt.ls.correction.convertLocalToField.assist"; //$NON-NLS-1$
	public static final String CONVERT_ANONYMOUS_TO_LOCAL_ID = "org.eclipse.jdt.ls.correction.convertAnonymousToLocal.assist"; //$NON-NLS-1$
	public static final String CONVERT_TO_STRING_BUFFER_ID = "org.eclipse.jdt.ls.correction.convertToStringBuffer.assist"; //$NON-NLS-1$
	public static final String CONVERT_TO_MESSAGE_FORMAT_ID = "org.eclipse.jdt.ls.correction.convertToMessageFormat.assist"; //$NON-NLS-1$;
	public static final String EXTRACT_METHOD_INPLACE_ID = "org.eclipse.jdt.ls.correction.extractMethodInplace.assist"; //$NON-NLS-1$;

	private PreferenceManager preferenceManager;

	public QuickAssistProcessor(PreferenceManager preferenceManager) {
		this.preferenceManager = preferenceManager;
	}

	public List<CUCorrectionProposal> getAssists(CodeActionParams params, IInvocationContext context, IProblemLocationCore[] locations) throws CoreException {
		ASTNode coveringNode = context.getCoveringNode();
		if (coveringNode != null) {
			ArrayList<ASTNode> coveredNodes = getFullyCoveredNodes(context, coveringNode);
			ArrayList<CUCorrectionProposal> resultingCollections = new ArrayList<>();
			boolean noErrorsAtLocation = noErrorsAtLocation(locations);

			// quick assists that show up also if there is an error/warning
			//			getRenameLocalProposals(context, coveringNode, locations, resultingCollections);
			//			getRenameRefactoringProposal(context, coveringNode, locations, resultingCollections);
			//			getAssignToVariableProposals(context, coveringNode, locations, resultingCollections);
			getAssignParamToFieldProposals(context, coveringNode, resultingCollections);
			getAssignAllParamsToFieldsProposals(context, coveringNode, resultingCollections);
			//			getInferDiamondArgumentsProposal(context, coveringNode, locations, resultingCollections);
			//			getGenerateForLoopProposals(context, coveringNode, locations, resultingCollections);

			if (noErrorsAtLocation) {
				boolean problemsAtLocation = locations.length != 0;
				//				getCatchClauseToThrowsProposals(context, coveringNode, resultingCollections);
				//				getPickoutTypeFromMulticatchProposals(context, coveringNode, coveredNodes, resultingCollections);
				//				getConvertToMultiCatchProposals(context, coveringNode, resultingCollections);
				//				getUnrollMultiCatchProposals(context, coveringNode, resultingCollections);
				//				getUnWrapProposals(context, coveringNode, resultingCollections);
				//				getJoinVariableProposals(context, coveringNode, resultingCollections);
				//				getSplitVariableProposals(context, coveringNode, resultingCollections);
				//				getAddFinallyProposals(context, coveringNode, resultingCollections);
				//				getAddElseProposals(context, coveringNode, resultingCollections);
				//				getAddBlockProposals(context, coveringNode, resultingCollections);
				//				getInvertEqualsProposal(context, coveringNode, resultingCollections);
				//				getArrayInitializerToArrayCreation(context, coveringNode, resultingCollections);
				//				getCreateInSuperClassProposals(context, coveringNode, resultingCollections);
				getExtractVariableProposal(params, context, problemsAtLocation, resultingCollections);
				getExtractMethodProposal(params, context, coveringNode, problemsAtLocation, resultingCollections);
				getExtractFieldProposal(params, context, problemsAtLocation, resultingCollections);
				//				getInlineLocalProposal(context, coveringNode, resultingCollections);
				//				getConvertLocalToFieldProposal(context, coveringNode, resultingCollections);
				//				getConvertAnonymousToNestedProposal(context, coveringNode, resultingCollections);
				getConvertAnonymousClassCreationsToLambdaProposals(context, coveringNode, resultingCollections);
				//				getConvertLambdaToAnonymousClassCreationsProposals(context, coveringNode, resultingCollections);
				//				getChangeLambdaBodyToBlockProposal(context, coveringNode, resultingCollections);
				//				getChangeLambdaBodyToExpressionProposal(context, coveringNode, resultingCollections);
				//				getAddInferredLambdaParameterTypes(context, coveringNode, resultingCollections);
				//				getConvertMethodReferenceToLambdaProposal(context, coveringNode, resultingCollections);
				//				getConvertLambdaToMethodReferenceProposal(context, coveringNode, resultingCollections);
				//				getFixParenthesesInLambdaExpression(context, coveringNode, resultingCollections);
				//				if (!getConvertForLoopProposal(context, coveringNode, resultingCollections)) {
				//					getConvertIterableLoopProposal(context, coveringNode, resultingCollections);
				//				}
				//				getConvertEnhancedForLoopProposal(context, coveringNode, resultingCollections);
				//				getRemoveBlockProposals(context, coveringNode, resultingCollections);
				//				getMakeVariableDeclarationFinalProposals(context, resultingCollections);
				//				getConvertStringConcatenationProposals(context, resultingCollections);
				//				getMissingCaseStatementProposals(context, coveringNode, resultingCollections);
				getConvertVarTypeToResolvedTypeProposal(context, coveringNode, resultingCollections);
				getConvertResolvedTypeToVarTypeProposal(context, coveringNode, resultingCollections);
				getAddStaticImportProposals(context, coveringNode, resultingCollections);
			}
			return resultingCollections;
		}
		return Collections.emptyList();
	}

	private static boolean getAssignParamToFieldProposals(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> resultingCollections) {
		node = ASTNodes.getNormalizedNode(node);
		ASTNode parent = node.getParent();
		if (!(parent instanceof SingleVariableDeclaration) || !(parent.getParent() instanceof MethodDeclaration)) {
			return false;
		}
		SingleVariableDeclaration paramDecl = (SingleVariableDeclaration) parent;
		IVariableBinding binding = paramDecl.resolveBinding();

		MethodDeclaration methodDecl = (MethodDeclaration) parent.getParent();
		if (binding == null || methodDecl.getBody() == null) {
			return false;
		}
		ITypeBinding typeBinding = binding.getType();
		if (typeBinding == null) {
			return false;
		}

		if (resultingCollections == null) {
			return true;
		}

		ITypeBinding parentType = Bindings.getBindingOfParentType(node);
		if (parentType != null) {
			if (parentType.isInterface()) {
				return false;
			}
			// assign to existing fields
			CompilationUnit root = context.getASTRoot();
			IVariableBinding[] declaredFields = parentType.getDeclaredFields();
			boolean isStaticContext = ASTResolving.isInStaticContext(node);
			for (int i = 0; i < declaredFields.length; i++) {
				IVariableBinding curr = declaredFields[i];
				if (isStaticContext == Modifier.isStatic(curr.getModifiers()) && typeBinding.isAssignmentCompatible(curr.getType())) {
					ASTNode fieldDeclFrag = root.findDeclaringNode(curr);
					if (fieldDeclFrag instanceof VariableDeclarationFragment) {
						VariableDeclarationFragment fragment = (VariableDeclarationFragment) fieldDeclFrag;
						if (fragment.getInitializer() == null) {
							resultingCollections.add(new AssignToVariableAssistProposal(context.getCompilationUnit(), paramDecl, fragment, typeBinding, IProposalRelevance.ASSIGN_PARAM_TO_EXISTING_FIELD));
						}
					}
				}
			}
		}

		AssignToVariableAssistProposal fieldProposal = new AssignToVariableAssistProposal(context.getCompilationUnit(), paramDecl, null, typeBinding, IProposalRelevance.ASSIGN_PARAM_TO_NEW_FIELD);
		resultingCollections.add(fieldProposal);
		return true;
	}

	private static boolean getAssignAllParamsToFieldsProposals(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> resultingCollections) {
		node = ASTNodes.getNormalizedNode(node);
		ASTNode parent = node.getParent();
		if (!(parent instanceof SingleVariableDeclaration) || !(parent.getParent() instanceof MethodDeclaration)) {
			return false;
		}
		MethodDeclaration methodDecl = (MethodDeclaration) parent.getParent();
		if (methodDecl.getBody() == null) {
			return false;
		}
		List<SingleVariableDeclaration> parameters = methodDecl.parameters();
		if (parameters.size() <= 1) {
			return false;
		}
		ITypeBinding parentType = Bindings.getBindingOfParentType(node);
		if (parentType == null || parentType.isInterface()) {
			return false;
		}
		for (SingleVariableDeclaration param : parameters) {
			IVariableBinding binding = param.resolveBinding();
			if (binding == null || binding.getType() == null) {
				return false;
			}
		}
		if (resultingCollections == null) {
			return true;
		}

		AssignToVariableAssistProposal fieldProposal = new AssignToVariableAssistProposal(context.getCompilationUnit(), parameters, IProposalRelevance.ASSIGN_ALL_PARAMS_TO_NEW_FIELDS);
		resultingCollections.add(fieldProposal);
		return true;
	}

	private static boolean getConvertVarTypeToResolvedTypeProposal(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> proposals) {
		CompilationUnit astRoot = context.getASTRoot();
		IJavaElement root = astRoot.getJavaElement();
		if (root == null) {
			return false;
		}
		IJavaProject javaProject = root.getJavaProject();
		if (javaProject == null) {
			return false;
		}
		if (!JavaModelUtil.is10OrHigher(javaProject)) {
			return false;
		}

		if (!(node instanceof SimpleName)) {
			return false;
		}
		SimpleName name = (SimpleName) node;
		IBinding binding = name.resolveBinding();
		if (!(binding instanceof IVariableBinding)) {
			return false;
		}
		IVariableBinding varBinding = (IVariableBinding) binding;
		if (varBinding.isField() || varBinding.isParameter()) {
			return false;
		}

		ASTNode varDeclaration = astRoot.findDeclaringNode(varBinding);
		if (varDeclaration == null) {
			return false;
		}

		ITypeBinding typeBinding = varBinding.getType();
		if (typeBinding == null || typeBinding.isAnonymous() || typeBinding.isIntersectionType() || typeBinding.isWildcardType()) {
			return false;
		}

		Type type = null;
		if (varDeclaration instanceof SingleVariableDeclaration) {
			type = ((SingleVariableDeclaration) varDeclaration).getType();
		} else if (varDeclaration instanceof VariableDeclarationFragment) {
			ASTNode parent = varDeclaration.getParent();
			if (parent instanceof VariableDeclarationStatement) {
				type = ((VariableDeclarationStatement) parent).getType();
			} else if (parent instanceof VariableDeclarationExpression) {
				type = ((VariableDeclarationExpression) parent).getType();
			}
		}
		if (type == null || !type.isVar()) {
			return false;
		}

		proposals.add(new TypeChangeCorrectionProposal(context.getCompilationUnit(), varBinding, astRoot, typeBinding, false, IProposalRelevance.CHANGE_VARIABLE));
		return true;
	}

	private static boolean getConvertResolvedTypeToVarTypeProposal(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> proposals) {
		CompilationUnit astRoot = context.getASTRoot();
		IJavaElement root = astRoot.getJavaElement();
		if (root == null) {
			return false;
		}
		IJavaProject javaProject = root.getJavaProject();
		if (javaProject == null) {
			return false;
		}
		if (!JavaModelUtil.is10OrHigher(javaProject)) {
			return false;
		}

		if (!(node instanceof SimpleName)) {
			return false;
		}
		SimpleName name = (SimpleName) node;
		IBinding binding = name.resolveBinding();
		if (!(binding instanceof IVariableBinding)) {
			return false;
		}
		IVariableBinding varBinding = (IVariableBinding) binding;
		if (varBinding.isField() || varBinding.isParameter()) {
			return false;
		}

		ASTNode varDeclaration = astRoot.findDeclaringNode(varBinding);
		if (varDeclaration == null) {
			return false;
		}

		Type type = null;
		Expression expression = null;

		ITypeBinding typeBinding = varBinding.getType();
		if (typeBinding == null) {
			return false;
		}
		ITypeBinding expressionTypeBinding = null;

		if (varDeclaration instanceof SingleVariableDeclaration) {
			SingleVariableDeclaration svDecl = (SingleVariableDeclaration) varDeclaration;
			type = svDecl.getType();
			expression = svDecl.getInitializer();
			if (expression != null) {
				expressionTypeBinding = expression.resolveTypeBinding();
			} else {
				ASTNode parent = svDecl.getParent();
				if (parent instanceof EnhancedForStatement) {
					EnhancedForStatement efStmt = (EnhancedForStatement) parent;
					expression = efStmt.getExpression();
					if (expression != null) {
						ITypeBinding expBinding = expression.resolveTypeBinding();
						if (expBinding != null) {
							if (expBinding.isArray()) {
								expressionTypeBinding = expBinding.getElementType();
							} else {
								ITypeBinding iterable = Bindings.findTypeInHierarchy(expBinding, "java.lang.Iterable"); //$NON-NLS-1$
								if (iterable != null) {
									ITypeBinding[] typeArguments = iterable.getTypeArguments();
									if (typeArguments.length == 1) {
										expressionTypeBinding = typeArguments[0];
										expressionTypeBinding = Bindings.normalizeForDeclarationUse(expressionTypeBinding, context.getASTRoot().getAST());
									}
								}
							}
						}
					}
				}
			}
		} else if (varDeclaration instanceof VariableDeclarationFragment) {
			ASTNode parent = varDeclaration.getParent();
			expression = ((VariableDeclarationFragment) varDeclaration).getInitializer();
			if (expression != null) {
				expressionTypeBinding = expression.resolveTypeBinding();
			}
			if (parent instanceof VariableDeclarationStatement) {
				type = ((VariableDeclarationStatement) parent).getType();
			} else if (parent instanceof VariableDeclarationExpression) {
				VariableDeclarationExpression varDecl = (VariableDeclarationExpression) parent;
				// cannot convert a VariableDeclarationExpression with multiple fragments to var.
				if (varDecl.fragments().size() > 1) {
					return false;
				}
				type = varDecl.getType();
			}
		}

		if (type == null || type.isVar()) {
			return false;
		}
		if (expression == null || expression instanceof ArrayInitializer || expression instanceof LambdaExpression || expression instanceof MethodReference) {
			return false;
		}
		if (expressionTypeBinding == null || !expressionTypeBinding.isEqualTo(typeBinding)) {
			return false;
		}

		proposals.add(new TypeChangeCorrectionProposal(context.getCompilationUnit(), varBinding, astRoot, typeBinding, IProposalRelevance.CHANGE_VARIABLE));
		return true;
	}
	static ArrayList<ASTNode> getFullyCoveredNodes(IInvocationContext context, ASTNode coveringNode) {
		final ArrayList<ASTNode> coveredNodes = new ArrayList<>();
		final int selectionBegin = context.getSelectionOffset();
		final int selectionEnd = selectionBegin + context.getSelectionLength();
		coveringNode.accept(new GenericVisitor() {
			@Override
			protected boolean visitNode(ASTNode node) {
				int nodeStart = node.getStartPosition();
				int nodeEnd = nodeStart + node.getLength();
				// if node does not intersects with selection, don't visit children
				if (nodeEnd < selectionBegin || selectionEnd < nodeStart) {
					return false;
				}
				// if node is fully covered, we don't need to visit children
				if (isCovered(node)) {
					ASTNode parent = node.getParent();
					if (parent == null || !isCovered(parent)) {
						coveredNodes.add(node);
						return false;
					}
				}
				// if node only partly intersects with selection, we try to find fully covered children
				return true;
			}

			private boolean isCovered(ASTNode node) {
				int begin = node.getStartPosition();
				int end = begin + node.getLength();
				return begin >= selectionBegin && end <= selectionEnd;
			}
		});
		return coveredNodes;
	}

	static boolean noErrorsAtLocation(IProblemLocationCore[] locations) {
		if (locations != null) {
			for (int i = 0; i < locations.length; i++) {
				IProblemLocationCore location = locations[i];
				if (location.isError()) {
					if (IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER.equals(location.getMarkerType()) && JavaCore.getOptionForConfigurableSeverity(location.getProblemId()) != null) {
						// continue (only drop out for severe (non-optional) errors)
					} else {
						return false;
					}
				}
			}
		}
		return true;
	}

	private boolean getExtractMethodProposal(CodeActionParams params, IInvocationContext context, ASTNode coveringNode, boolean problemsAtLocation, Collection<CUCorrectionProposal> proposals) throws CoreException {
		if (proposals == null) {
			return false;
		}

		CUCorrectionProposal proposal = null;
		if (this.preferenceManager.getClientPreferences().isAdvancedExtractRefactoringSupported()) {
			proposal = ExtractProposalUtility.getExtractMethodCommandProposal(params, context, coveringNode, problemsAtLocation);
		} else {
			proposal = ExtractProposalUtility.getExtractMethodProposal(params, context, coveringNode, problemsAtLocation);
		}

		if (proposal == null) {
			return false;
		}

		proposals.add(proposal);
		return true;
	}

	private boolean getExtractFieldProposal(CodeActionParams params, IInvocationContext context, boolean problemsAtLocation, Collection<CUCorrectionProposal> proposals) throws CoreException {
		if (proposals == null) {
			return false;
		}

		CUCorrectionProposal proposal = ExtractProposalUtility.getGenericExtractFieldProposal(params, context, problemsAtLocation, null, null, this.preferenceManager.getClientPreferences().isAdvancedExtractRefactoringSupported());

		if (proposal == null) {
			return false;
		}

		proposals.add(proposal);
		return true;
	}

	private static boolean getConvertAnonymousClassCreationsToLambdaProposals(IInvocationContext context, ASTNode covering, Collection<CUCorrectionProposal> resultingCollections) {
		while (covering instanceof Name || covering instanceof Type || covering instanceof Dimension || covering.getParent() instanceof MethodDeclaration
				|| covering.getLocationInParent() == AnonymousClassDeclaration.BODY_DECLARATIONS_PROPERTY) {
			covering = covering.getParent();
		}

		ClassInstanceCreation cic;
		if (covering instanceof ClassInstanceCreation) {
			cic = (ClassInstanceCreation) covering;
		} else if (covering.getLocationInParent() == ClassInstanceCreation.ANONYMOUS_CLASS_DECLARATION_PROPERTY) {
			cic = (ClassInstanceCreation) covering.getParent();
		} else if (covering instanceof Name) {
			ASTNode normalized = ASTNodes.getNormalizedNode(covering);
			if (normalized.getLocationInParent() != ClassInstanceCreation.TYPE_PROPERTY) {
				return false;
			}
			cic = (ClassInstanceCreation) normalized.getParent();
		} else {
			return false;
		}

		IProposableFix fix = LambdaExpressionsFix.createConvertToLambdaFix(cic);
		if (fix == null) {
			return false;
		}

		if (resultingCollections == null) {
			return true;
		}

		Map<String, String> options = new HashMap<>();
		options.put(CleanUpConstants.CONVERT_FUNCTIONAL_INTERFACES, CleanUpOptionsCore.TRUE);
		options.put(CleanUpConstants.USE_LAMBDA, CleanUpOptionsCore.TRUE);
		FixCorrectionProposal proposal = new FixCorrectionProposal(fix, new LambdaExpressionsCleanUp(options), IProposalRelevance.CONVERT_TO_LAMBDA_EXPRESSION, context);
		resultingCollections.add(proposal);
		return true;
	}

	private boolean getExtractVariableProposal(CodeActionParams params, IInvocationContext context, boolean problemsAtLocation, Collection<CUCorrectionProposal> proposals) throws CoreException {
		if (proposals == null) {
			return false;
		}

		List<CUCorrectionProposal> newProposals = null;
		if (this.preferenceManager.getClientPreferences().isAdvancedExtractRefactoringSupported()) {
			newProposals = ExtractProposalUtility.getExtractVariableCommandProposals(params, context, problemsAtLocation);
		} else {
			newProposals = ExtractProposalUtility.getExtractVariableProposals(params, context, problemsAtLocation);
		}

		if (newProposals == null || newProposals.isEmpty()) {
			return false;
		}

		proposals.addAll(newProposals);
		return true;
	}

	/**
	 * Returns the functional interface method being implemented by the given method
	 * reference.
	 *
	 * @param methodReference
	 *            the method reference to get the functional method
	 * @return the functional interface method being implemented by
	 *         <code>methodReference</code> or <code>null</code> if it could not be
	 *         derived
	 */
	public static IMethodBinding getFunctionalMethodForMethodReference(MethodReference methodReference) {
		ITypeBinding targetTypeBinding = ASTNodes.getTargetType(methodReference);
		if (targetTypeBinding == null) {
			return null;
		}

		IMethodBinding functionalMethod = targetTypeBinding.getFunctionalInterfaceMethod();
		if (functionalMethod.isSynthetic()) {
			functionalMethod = Bindings.findOverriddenMethodInType(functionalMethod.getDeclaringClass(), functionalMethod);
		}
		return functionalMethod;
	}

	/**
	 * Converts and replaces the given method reference with corresponding lambda
	 * expression in the given ASTRewrite.
	 *
	 * @param methodReference
	 *            the method reference to convert
	 * @param functionalMethod
	 *            the non-generic functional interface method to be implemented by
	 *            the lambda expression
	 * @param astRoot
	 *            the AST root
	 * @param rewrite
	 *            the ASTRewrite
	 * @param linkedProposalModel
	 *            to create linked proposals for lambda's parameters or
	 *            <code>null</code> if linked proposals are not required
	 * @param createBlockBody
	 *            <code>true</code> if lambda expression's body should be a block
	 *
	 * @return lambda expression used to replace the method reference in the given
	 *         ASTRewrite
	 * @throws JavaModelException
	 *             if an exception occurs while accessing the Java element
	 *             corresponding to the <code>functionalMethod</code>
	 */
	public static LambdaExpression convertMethodRefernceToLambda(MethodReference methodReference, IMethodBinding functionalMethod, CompilationUnit astRoot, ASTRewrite rewrite, LinkedProposalModelCore linkedProposalModel,
			boolean createBlockBody) throws JavaModelException {

		AST ast = astRoot.getAST();
		LambdaExpression lambda = ast.newLambdaExpression();

		String[] lambdaParamNames = getUniqueParameterNames(methodReference, functionalMethod);
		List<VariableDeclaration> lambdaParameters = lambda.parameters();
		for (int i = 0; i < lambdaParamNames.length; i++) {
			String paramName = lambdaParamNames[i];
			VariableDeclarationFragment lambdaParameter = ast.newVariableDeclarationFragment();
			SimpleName name = ast.newSimpleName(paramName);
			lambdaParameter.setName(name);
			lambdaParameters.add(lambdaParameter);
			if (linkedProposalModel != null) {
				linkedProposalModel.getPositionGroup(name.getIdentifier(), true).addPosition(rewrite.track(name), i == 0);
			}
		}

		int noOfLambdaParameters = lambdaParamNames.length;
		lambda.setParentheses(noOfLambdaParameters != 1);

		ITypeBinding returnTypeBinding = functionalMethod.getReturnType();
		IMethodBinding referredMethodBinding = methodReference.resolveMethodBinding(); // too often null, see bug 440000, bug 440344, bug 333665

		if (methodReference instanceof CreationReference) {
			CreationReference creationRef = (CreationReference) methodReference;
			Type type = creationRef.getType();
			if (type instanceof ArrayType) {
				ArrayCreation arrayCreation = ast.newArrayCreation();
				if (createBlockBody) {
					Block blockBody = getBlockBodyForLambda(arrayCreation, returnTypeBinding, ast);
					lambda.setBody(blockBody);
				} else {
					lambda.setBody(arrayCreation);
				}

				ArrayType arrayType = (ArrayType) type;
				Type copiedElementType = (Type) rewrite.createCopyTarget(arrayType.getElementType());
				arrayCreation.setType(ast.newArrayType(copiedElementType, arrayType.getDimensions()));
				SimpleName name = ast.newSimpleName(lambdaParamNames[0]);
				arrayCreation.dimensions().add(name);
				if (linkedProposalModel != null) {
					linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
				}
			} else {
				ClassInstanceCreation cic = ast.newClassInstanceCreation();
				if (createBlockBody) {
					Block blockBody = getBlockBodyForLambda(cic, returnTypeBinding, ast);
					lambda.setBody(blockBody);
				} else {
					lambda.setBody(cic);
				}

				ITypeBinding typeBinding = type.resolveBinding();
				if (!(type instanceof ParameterizedType) && typeBinding != null && typeBinding.getTypeDeclaration().isGenericType()) {
					cic.setType(ast.newParameterizedType((Type) rewrite.createCopyTarget(type)));
				} else {
					cic.setType((Type) rewrite.createCopyTarget(type));
				}
				List<SimpleName> invocationArgs = getInvocationArguments(ast, 0, noOfLambdaParameters, lambdaParamNames);
				cic.arguments().addAll(invocationArgs);
				if (linkedProposalModel != null) {
					for (SimpleName name : invocationArgs) {
						linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
					}
				}
				cic.typeArguments().addAll(getCopiedTypeArguments(rewrite, methodReference.typeArguments()));
			}

		} else if (referredMethodBinding != null && Modifier.isStatic(referredMethodBinding.getModifiers())) {
			MethodInvocation methodInvocation = ast.newMethodInvocation();
			if (createBlockBody) {
				Block blockBody = getBlockBodyForLambda(methodInvocation, returnTypeBinding, ast);
				lambda.setBody(blockBody);
			} else {
				lambda.setBody(methodInvocation);
			}

			Expression expr = null;
			boolean hasConflict = hasConflict(methodReference.getStartPosition(), referredMethodBinding, ScopeAnalyzer.METHODS | ScopeAnalyzer.CHECK_VISIBILITY, astRoot);
			if (hasConflict || !Bindings.isSuperType(referredMethodBinding.getDeclaringClass(), ASTNodes.getEnclosingType(methodReference)) || methodReference.typeArguments().size() != 0) {
				if (methodReference instanceof ExpressionMethodReference) {
					ExpressionMethodReference expressionMethodReference = (ExpressionMethodReference) methodReference;
					expr = (Expression) rewrite.createCopyTarget(expressionMethodReference.getExpression());
				} else if (methodReference instanceof TypeMethodReference) {
					Type type = ((TypeMethodReference) methodReference).getType();
					ITypeBinding typeBinding = type.resolveBinding();
					if (typeBinding != null) {
						ImportRewrite importRewrite = CodeStyleConfiguration.createImportRewrite(astRoot, true);
						expr = ast.newName(importRewrite.addImport(typeBinding));
					}
				}
			}
			methodInvocation.setExpression(expr);
			SimpleName methodName = getMethodInvocationName(methodReference);
			methodInvocation.setName((SimpleName) rewrite.createCopyTarget(methodName));
			List<SimpleName> invocationArgs = getInvocationArguments(ast, 0, noOfLambdaParameters, lambdaParamNames);
			methodInvocation.arguments().addAll(invocationArgs);
			if (linkedProposalModel != null) {
				for (SimpleName name : invocationArgs) {
					linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
				}
			}
			methodInvocation.typeArguments().addAll(getCopiedTypeArguments(rewrite, methodReference.typeArguments()));

		} else if (methodReference instanceof SuperMethodReference) {
			SuperMethodInvocation superMethodInvocation = ast.newSuperMethodInvocation();
			if (createBlockBody) {
				Block blockBody = getBlockBodyForLambda(superMethodInvocation, returnTypeBinding, ast);
				lambda.setBody(blockBody);
			} else {
				lambda.setBody(superMethodInvocation);
			}

			Name superQualifier = ((SuperMethodReference) methodReference).getQualifier();
			if (superQualifier != null) {
				superMethodInvocation.setQualifier((Name) rewrite.createCopyTarget(superQualifier));
			}
			SimpleName methodName = getMethodInvocationName(methodReference);
			superMethodInvocation.setName((SimpleName) rewrite.createCopyTarget(methodName));
			List<SimpleName> invocationArgs = getInvocationArguments(ast, 0, noOfLambdaParameters, lambdaParamNames);
			superMethodInvocation.arguments().addAll(invocationArgs);
			if (linkedProposalModel != null) {
				for (SimpleName name : invocationArgs) {
					linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
				}
			}
			superMethodInvocation.typeArguments().addAll(getCopiedTypeArguments(rewrite, methodReference.typeArguments()));

		} else {
			MethodInvocation methodInvocation = ast.newMethodInvocation();
			if (createBlockBody) {
				Block blockBody = getBlockBodyForLambda(methodInvocation, returnTypeBinding, ast);
				lambda.setBody(blockBody);
			} else {
				lambda.setBody(methodInvocation);
			}

			boolean isTypeReference = isTypeReferenceToInstanceMethod(methodReference);
			if (isTypeReference) {
				SimpleName name = ast.newSimpleName(lambdaParamNames[0]);
				methodInvocation.setExpression(name);
				if (linkedProposalModel != null) {
					linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
				}
			} else {
				Expression expr = ((ExpressionMethodReference) methodReference).getExpression();
				if (!(expr instanceof ThisExpression && methodReference.typeArguments().size() == 0)) {
					methodInvocation.setExpression((Expression) rewrite.createCopyTarget(expr));
				}
			}
			SimpleName methodName = getMethodInvocationName(methodReference);
			methodInvocation.setName((SimpleName) rewrite.createCopyTarget(methodName));
			List<SimpleName> invocationArgs = getInvocationArguments(ast, isTypeReference ? 1 : 0, noOfLambdaParameters, lambdaParamNames);
			methodInvocation.arguments().addAll(invocationArgs);
			if (linkedProposalModel != null) {
				for (SimpleName name : invocationArgs) {
					linkedProposalModel.getPositionGroup(name.getIdentifier(), false).addPosition(rewrite.track(name), LinkedPositionGroup.NO_STOP);
				}
			}
			methodInvocation.typeArguments().addAll(getCopiedTypeArguments(rewrite, methodReference.typeArguments()));
		}

		rewrite.replace(methodReference, lambda, null);
		return lambda;
	}

	private static boolean hasConflict(int startPosition, IMethodBinding referredMethodBinding, int flags, CompilationUnit cu) {
		ScopeAnalyzer analyzer = new ScopeAnalyzer(cu);
		IBinding[] declarationsInScope = analyzer.getDeclarationsInScope(startPosition, flags);
		for (int i = 0; i < declarationsInScope.length; i++) {
			IBinding decl = declarationsInScope[i];
			if (decl.getName().equals(referredMethodBinding.getName()) && !referredMethodBinding.getMethodDeclaration().isEqualTo(decl)) {
				return true;
			}
		}
		return false;
	}

	private static String[] getUniqueParameterNames(MethodReference methodReference, IMethodBinding functionalMethod) throws JavaModelException {
		String[] parameterNames = ((IMethod) functionalMethod.getJavaElement()).getParameterNames();
		List<String> oldNames = new ArrayList<>(Arrays.asList(parameterNames));
		String[] newNames = new String[oldNames.size()];
		List<String> excludedNames = new ArrayList<>(ASTNodes.getVisibleLocalVariablesInScope(methodReference));

		for (int i = 0; i < oldNames.size(); i++) {
			String paramName = oldNames.get(i);
			List<String> allNamesToExclude = new ArrayList<>(excludedNames);
			allNamesToExclude.addAll(oldNames.subList(0, i));
			allNamesToExclude.addAll(oldNames.subList(i + 1, oldNames.size()));
			if (allNamesToExclude.contains(paramName)) {
				String newParamName = createName(paramName, allNamesToExclude);
				excludedNames.add(newParamName);
				newNames[i] = newParamName;
			} else {
				newNames[i] = paramName;
			}
		}
		return newNames;
	}

	private static String createName(String candidate, List<String> excludedNames) {
		int i = 1;
		String result = candidate;
		while (excludedNames.contains(result)) {
			result = candidate + i++;
		}
		return result;
	}

	private static boolean isTypeReferenceToInstanceMethod(MethodReference methodReference) {
		if (methodReference instanceof TypeMethodReference) {
			return true;
		}
		if (methodReference instanceof ExpressionMethodReference) {
			Expression expression = ((ExpressionMethodReference) methodReference).getExpression();
			if (expression instanceof Name) {
				IBinding nameBinding = ((Name) expression).resolveBinding();
				if (nameBinding != null && nameBinding instanceof ITypeBinding) {
					return true;
				}
			}
		}
		return false;
	}

	private static List<SimpleName> getInvocationArguments(AST ast, int begIndex, int noOfLambdaParameters, String[] lambdaParamNames) {
		List<SimpleName> args = new ArrayList<>();
		for (int i = begIndex; i < noOfLambdaParameters; i++) {
			args.add(ast.newSimpleName(lambdaParamNames[i]));
		}
		return args;
	}

	private static List<Type> getCopiedTypeArguments(ASTRewrite rewrite, List<Type> typeArguments) {
		List<Type> copiedTypeArgs = new ArrayList<>();
		for (Type typeArg : typeArguments) {
			copiedTypeArgs.add((Type) rewrite.createCopyTarget(typeArg));
		}
		return copiedTypeArgs;
	}

	private static SimpleName getMethodInvocationName(MethodReference methodReference) {
		SimpleName name = null;
		if (methodReference instanceof ExpressionMethodReference) {
			name = ((ExpressionMethodReference) methodReference).getName();
		} else if (methodReference instanceof TypeMethodReference) {
			name = ((TypeMethodReference) methodReference).getName();
		} else if (methodReference instanceof SuperMethodReference) {
			name = ((SuperMethodReference) methodReference).getName();
		}
		return name;
	}

	public static void changeLambdaBodyToBlock(LambdaExpression lambda, AST ast, ASTRewrite rewrite) {
		Expression bodyExpr = (Expression) rewrite.createMoveTarget(lambda.getBody());
		Block blockBody = getBlockBodyForLambda(bodyExpr, lambda.resolveMethodBinding().getReturnType(), ast);
		rewrite.set(lambda, LambdaExpression.BODY_PROPERTY, blockBody, null);
	}

	private static Block getBlockBodyForLambda(Expression bodyExpr, ITypeBinding returnTypeBinding, AST ast) {
		Statement statementInBlockBody;
		if (ast.resolveWellKnownType("void").isEqualTo(returnTypeBinding)) { //$NON-NLS-1$
			ExpressionStatement expressionStatement = ast.newExpressionStatement(bodyExpr);
			statementInBlockBody = expressionStatement;
		} else {
			ReturnStatement returnStatement = ast.newReturnStatement();
			returnStatement.setExpression(bodyExpr);
			statementInBlockBody = returnStatement;
		}
		Block blockBody = ast.newBlock();
		blockBody.statements().add(statementInBlockBody);
		return blockBody;
	}

	public static boolean getCatchClauseToThrowsProposals(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> resultingCollections) {
		if (resultingCollections == null) {
			return true;
		}

		CatchClause catchClause = (CatchClause) ASTResolving.findAncestor(node, ASTNode.CATCH_CLAUSE);
		if (catchClause == null) {
			return false;
		}

		Statement statement = ASTResolving.findParentStatement(node);
		if (statement != catchClause.getParent() && statement != catchClause.getBody()) {
			return false; // selection is in a statement inside the body
		}

		Type type = catchClause.getException().getType();
		if (!type.isSimpleType() && !type.isUnionType() && !type.isNameQualifiedType()) {
			return false;
		}

		BodyDeclaration bodyDeclaration = ASTResolving.findParentBodyDeclaration(catchClause);
		if (!(bodyDeclaration instanceof MethodDeclaration) && !(bodyDeclaration instanceof Initializer)) {
			return false;
		}

		AST ast = bodyDeclaration.getAST();

		Type selectedMultiCatchType = null;
		if (type.isUnionType() && node instanceof Name) {
			Name topMostName = ASTNodes.getTopMostName((Name) node);
			ASTNode parent = topMostName.getParent();
			if (parent instanceof SimpleType) {
				selectedMultiCatchType = (SimpleType) parent;
			} else if (parent instanceof NameQualifiedType) {
				selectedMultiCatchType = (NameQualifiedType) parent;
			}
		}

		if (bodyDeclaration instanceof MethodDeclaration) {
			MethodDeclaration methodDeclaration = (MethodDeclaration) bodyDeclaration;

			ASTRewrite rewrite = ASTRewrite.create(ast);
			if (selectedMultiCatchType != null) {
				removeException(rewrite, (UnionType) type, selectedMultiCatchType);
				addExceptionToThrows(ast, methodDeclaration, rewrite, selectedMultiCatchType);
				String label = CorrectionMessages.QuickAssistProcessor_exceptiontothrows_description;
				ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(), rewrite, IProposalRelevance.REPLACE_EXCEPTION_WITH_THROWS);
				resultingCollections.add(proposal);
			} else {
				removeCatchBlock(rewrite, catchClause);
				if (type.isUnionType()) {
					UnionType unionType = (UnionType) type;
					List<Type> types = unionType.types();
					for (Type elementType : types) {
						if (!(elementType instanceof SimpleType || elementType instanceof NameQualifiedType)) {
							return false;
						}
						addExceptionToThrows(ast, methodDeclaration, rewrite, elementType);
					}
				} else {
					addExceptionToThrows(ast, methodDeclaration, rewrite, type);
				}
				String label = CorrectionMessages.QuickAssistProcessor_catchclausetothrows_description;
				ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(), rewrite, IProposalRelevance.REPLACE_CATCH_CLAUSE_WITH_THROWS);
				resultingCollections.add(proposal);
			}
		}
		{ // for initializers or method declarations
			ASTRewrite rewrite = ASTRewrite.create(ast);
			if (selectedMultiCatchType != null) {
				removeException(rewrite, (UnionType) type, selectedMultiCatchType);
				String label = CorrectionMessages.QuickAssistProcessor_removeexception_description;
				ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(), rewrite, IProposalRelevance.REMOVE_EXCEPTION);
				resultingCollections.add(proposal);
			} else {
				removeCatchBlock(rewrite, catchClause);
				String label = CorrectionMessages.QuickAssistProcessor_removecatchclause_description;
				ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(label, CodeActionKind.QuickFix, context.getCompilationUnit(), rewrite, IProposalRelevance.REMOVE_CATCH_CLAUSE);
				resultingCollections.add(proposal);
			}
		}

		return true;
	}

	private static void removeException(ASTRewrite rewrite, UnionType unionType, Type exception) {
		ListRewrite listRewrite = rewrite.getListRewrite(unionType, UnionType.TYPES_PROPERTY);
		List<Type> types = unionType.types();
		for (Iterator<Type> iterator = types.iterator(); iterator.hasNext();) {
			Type type = iterator.next();
			if (type.equals(exception)) {
				listRewrite.remove(type, null);
			}
		}
	}

	private static void addExceptionToThrows(AST ast, MethodDeclaration methodDeclaration, ASTRewrite rewrite, Type type2) {
		ITypeBinding binding = type2.resolveBinding();
		if (binding == null || isNotYetThrown(binding, methodDeclaration.thrownExceptionTypes())) {
			Type newType = (Type) ASTNode.copySubtree(ast, type2);

			ListRewrite listRewriter = rewrite.getListRewrite(methodDeclaration, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
			listRewriter.insertLast(newType, null);
		}
	}

	private static void removeCatchBlock(ASTRewrite rewrite, CatchClause catchClause) {
		TryStatement tryStatement = (TryStatement) catchClause.getParent();
		if (tryStatement.catchClauses().size() > 1 || tryStatement.getFinally() != null || !tryStatement.resources().isEmpty()) {
			rewrite.remove(catchClause, null);
		} else {
			Block block = tryStatement.getBody();
			List<Statement> statements = block.statements();
			int nStatements = statements.size();
			if (nStatements == 1) {
				ASTNode first = statements.get(0);
				rewrite.replace(tryStatement, rewrite.createCopyTarget(first), null);
			} else if (nStatements > 1) {
				ListRewrite listRewrite = rewrite.getListRewrite(block, Block.STATEMENTS_PROPERTY);
				ASTNode first = statements.get(0);
				ASTNode last = statements.get(statements.size() - 1);
				ASTNode newStatement = listRewrite.createCopyTarget(first, last);
				if (ASTNodes.isControlStatementBody(tryStatement.getLocationInParent())) {
					Block newBlock = rewrite.getAST().newBlock();
					newBlock.statements().add(newStatement);
					newStatement = newBlock;
				}
				rewrite.replace(tryStatement, newStatement, null);
			} else {
				rewrite.remove(tryStatement, null);
			}
		}
	}

	private static boolean isNotYetThrown(ITypeBinding binding, List<Type> thrownExceptions) {
		for (Type thrownException : thrownExceptions) {
			ITypeBinding elem = thrownException.resolveBinding();
			if (elem != null) {
				if (Bindings.isSuperType(elem, binding)) { // existing exception is base class of new
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Create static import proposal
	 *
	 * @param context
	 *            the invocation context
	 * @param node
	 *            the node to work on
	 * @param proposals
	 *            the receiver of proposals, may be {@code null}
	 * @return {@code true} if the operation could or has been performed,
	 *         {@code false otherwise}
	 */
	private static boolean getAddStaticImportProposals(IInvocationContext context, ASTNode node, Collection<CUCorrectionProposal> proposals) {
		if (!(node instanceof SimpleName)) {
			return false;
		}

		final SimpleName name = (SimpleName) node;
		final IBinding binding;
		final ITypeBinding declaringClass;

		// get bindings for method invocation or variable access

		if (name.getParent() instanceof MethodInvocation) {
			MethodInvocation mi = (MethodInvocation) name.getParent();

			Expression expression = mi.getExpression();
			if (expression == null || expression.equals(name)) {
				return false;
			}

			binding = mi.resolveMethodBinding();
			if (binding == null) {
				return false;
			}

			declaringClass = ((IMethodBinding) binding).getDeclaringClass();
		} else if (name.getParent() instanceof QualifiedName) {
			QualifiedName qn = (QualifiedName) name.getParent();

			if (name.equals(qn.getQualifier()) || qn.getParent() instanceof ImportDeclaration) {
				return false;
			}

			binding = qn.resolveBinding();
			if (!(binding instanceof IVariableBinding)) {
				return false;
			}
			declaringClass = ((IVariableBinding) binding).getDeclaringClass();
		} else {
			return false;
		}

		// at this point binding cannot be null

		if (!Modifier.isStatic(binding.getModifiers())) {
			// only work with static bindings
			return false;
		}

		boolean needImport = false;
		if (!isDirectlyAccessible(name, declaringClass)) {
			if (Modifier.isPrivate(declaringClass.getModifiers())) {
				return false;
			}
			needImport = true;
		}

		if (proposals == null) {
			return true; // return early, just testing if we could do it
		}

		try {
			ImportRewrite importRewrite = StubUtility.createImportRewrite(context.getCompilationUnit(), true);
			ImportRewrite importRewriteReplaceAllOccurences = StubUtility.createImportRewrite(context.getCompilationUnit(), true);
			ASTRewrite astRewrite = ASTRewrite.create(node.getAST());
			ASTRewrite astRewriteReplaceAllOccurrences = ASTRewrite.create(node.getAST());

			int[] allReferencesToDeclaringClass = new int[1];
			allReferencesToDeclaringClass[0] = 0;
			int[] referencesFromOtherOccurences = new int[1];
			referencesFromOtherOccurences[0] = 0;
			MethodInvocation mi = null;
			QualifiedName qn = null;
			if (name.getParent() instanceof MethodInvocation) {
				mi = (MethodInvocation) name.getParent();
				// convert the method invocation
				astRewrite.remove(mi.getExpression(), null);
				mi.typeArguments().forEach(type -> astRewrite.remove((Type) type, null));
			} else if (name.getParent() instanceof QualifiedName) {
				qn = (QualifiedName) name.getParent();
				// convert the field access
				astRewrite.replace(qn, ASTNodeFactory.newName(node.getAST(), name.getFullyQualifiedName()), null);
			} else {
				return false;
			}

			MethodInvocation miFinal = mi;
			name.getRoot().accept(new ASTVisitor() {
				@Override
				public boolean visit(MethodInvocation methodInvocation) {
					Expression methodInvocationExpression = methodInvocation.getExpression();
					if (methodInvocationExpression == null) {
						return super.visit(methodInvocation);
					}

					if (methodInvocationExpression instanceof Name) {
						String fullyQualifiedName = ((Name) methodInvocationExpression).getFullyQualifiedName();
						if (miFinal != null && miFinal.getExpression() instanceof Name && ((Name) miFinal.getExpression()).getFullyQualifiedName().equals(fullyQualifiedName)
								&& miFinal.getName().getIdentifier().equals(methodInvocation.getName().getIdentifier())) {
							methodInvocation.typeArguments().forEach(type -> astRewriteReplaceAllOccurrences.remove((Type) type, null));
							astRewriteReplaceAllOccurrences.remove(methodInvocationExpression, null);
							allReferencesToDeclaringClass[0]++;
						} else if (declaringClass.getName().equals(fullyQualifiedName)) {
							allReferencesToDeclaringClass[0]++;
							referencesFromOtherOccurences[0]++;
						}
					} else if (methodInvocationExpression instanceof ClassInstanceCreation) {
						ClassInstanceCreation classInstanceCreation = (ClassInstanceCreation) methodInvocationExpression;
						if (classInstanceCreation.getType() instanceof SimpleType) {
							String typeName = ((SimpleType) classInstanceCreation.getType()).getName().getFullyQualifiedName();
							if (typeName.equals(declaringClass.getName())) {
								allReferencesToDeclaringClass[0]++;
								referencesFromOtherOccurences[0]++;
							}
						}
					}

					return super.visit(methodInvocation);
				}
			});
			QualifiedName qnFinal = qn;
			name.getRoot().accept(new ASTVisitor() {
				@Override
				public boolean visit(QualifiedName qualifiedName) {
					if (qnFinal != null && qualifiedName.getFullyQualifiedName().equals(qnFinal.getFullyQualifiedName())) {
						astRewriteReplaceAllOccurrences.replace(qualifiedName, ASTNodeFactory.newName(node.getAST(), name.getFullyQualifiedName()), null);
						allReferencesToDeclaringClass[0]++;
					} else if (declaringClass.getName().equals(qualifiedName.getQualifier().getFullyQualifiedName())) {
						allReferencesToDeclaringClass[0]++;
						referencesFromOtherOccurences[0]++;
					}
					return super.visit(qualifiedName);
				}
			});

			// add the static import
			if (needImport) {
				importRewrite.addStaticImport(binding);
				if (allReferencesToDeclaringClass[0] == 1) { // If there are exactly 1 visits, the import can be removed
					importRewrite.removeImport(declaringClass.getQualifiedName());
				}
				importRewriteReplaceAllOccurences.addStaticImport(binding);
				if (referencesFromOtherOccurences[0] == 0) {
					importRewriteReplaceAllOccurences.removeImport(declaringClass.getQualifiedName());
				}
			}

			ASTRewriteCorrectionProposal proposal = new ASTRewriteCorrectionProposal(CorrectionMessages.QuickAssistProcessor_convert_to_static_import, CodeActionKind.QuickFix, context.getCompilationUnit(), astRewrite,
					IProposalRelevance.ADD_STATIC_IMPORT);
			proposal.setImportRewrite(importRewrite);
			proposals.add(proposal);
			ASTRewriteCorrectionProposal proposalReplaceAllOccurrences = new ASTRewriteCorrectionProposal(CorrectionMessages.QuickAssistProcessor_convert_to_static_import_replace_all, CodeActionKind.QuickFix, context.getCompilationUnit(),
					astRewriteReplaceAllOccurrences, IProposalRelevance.ADD_STATIC_IMPORT);
			proposalReplaceAllOccurrences.setImportRewrite(importRewriteReplaceAllOccurences);
			proposals.add(proposalReplaceAllOccurrences);
		} catch (IllegalArgumentException e) {
			// Wrong use of ASTRewrite or ImportRewrite API, see bug 541586
			JavaLanguageServerPlugin.logException("Failed to get static import proposal", e);
			return false;
		} catch (JavaModelException e) {
			return false;
		}

		return true;
	}

	private static boolean isDirectlyAccessible(ASTNode nameNode, ITypeBinding declaringClass) {
		ASTNode node = nameNode.getParent();
		while (node != null) {

			if (node instanceof AbstractTypeDeclaration) {
				ITypeBinding binding = ((AbstractTypeDeclaration) node).resolveBinding();
				if (binding != null && binding.isSubTypeCompatible(declaringClass)) {
					return true;
				}
			} else if (node instanceof AnonymousClassDeclaration) {
				ITypeBinding binding = ((AnonymousClassDeclaration) node).resolveBinding();
				if (binding != null && binding.isSubTypeCompatible(declaringClass)) {
					return true;
				}
			}

			node = node.getParent();
		}
		return false;
	}
}
