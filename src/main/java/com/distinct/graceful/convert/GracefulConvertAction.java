package com.distinct.graceful.convert;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author zhoujintao
 */
public class GracefulConvertAction extends AnAction {

    public static Set<String> DATA_TYPE_SET = new HashSet<>();

    public final static String TARGET_NAME = "target";

    public final static String SOURCE_NAME = "source";

    public final static String COLLECT_METHOD_TEMPLATE = "public static %s convert2%ss (%s  " + SOURCE_NAME + "s) {\n";

    public static List<String> GENERATE_METHOD_TEXT_LIST = new ArrayList<>();

    public static String currentFileClassName;

    static {
        DATA_TYPE_SET.add("LocalDateTime");
        DATA_TYPE_SET.add("LocalDate");
        DATA_TYPE_SET.add("Date");
        DATA_TYPE_SET.add("String");
        DATA_TYPE_SET.add("BigDecimal");
    }


    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        PsiMethod psiMethod = getPsiMethod(event);
        if (psiMethod == null) {
            return;
        }

        PsiType returnClassType = psiMethod.getReturnType();
        if (returnClassType == null) {
            return;
        }

        currentFileClassName = psiMethod.getContainingClass().getName();

        PsiType paramClassType = psiMethod.getParameterList().getParameters()[0].getType();

        GenerateTemplateManager.generate(returnClassType, paramClassType);

        WriteCommandAction.runWriteCommandAction(psiMethod.getProject(), () -> {
            render(psiMethod);
        });

        GENERATE_METHOD_TEXT_LIST.clear();
        currentFileClassName = null;
    }

    private void render(PsiMethod psiMethod) {
        Project project = psiMethod.getProject();
        PsiMethod last = psiMethod;

        for (int i = GENERATE_METHOD_TEXT_LIST.size() - 1; i >= 0; i--) {

            String methodText = GENERATE_METHOD_TEXT_LIST.get(i);

            PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(project);

            PsiMethod toMethod = elementFactory.createMethodFromText(methodText, null);

            JavaCodeStyleManager.getInstance(psiMethod.getProject()).shortenClassReferences(toMethod, JavaCodeStyleManager.INCOMPLETE_CODE);

            CodeStyleManager.getInstance(psiMethod.getProject()).reformat(toMethod);

            psiMethod.getContainingClass().addAfter(toMethod, last);
        }

        psiMethod.delete();
    }


    public static boolean isDiffObjAndNotPrimitiveType(PsiType to, PsiType from) {
        return !(to instanceof PsiPrimitiveType) && !DATA_TYPE_SET.contains(to.getPresentableText()) && !to.getInternalCanonicalText().equals(from.getInternalCanonicalText()) && !(from instanceof PsiPrimitiveType);
    }

    public static boolean isListOrSet(PsiType to, PsiType from) {
        if (isList(to, from) || isSet(to, from)) {
            return true;
        }
        return false;
    }

    public static boolean isList(PsiType to, PsiType from) {
        String toPresentableText = to.getPresentableText();
        String fromPresentableText = from.getPresentableText();
        return (toPresentableText.startsWith("List") || toPresentableText.startsWith("ArrayList"))
                && (fromPresentableText.startsWith("List") || fromPresentableText.startsWith("ArrayList"));
    }

    public static boolean isSet(PsiType to, PsiType from) {
        String toPresentableText = to.getPresentableText();
        String fromPresentableText = from.getPresentableText();
        return (toPresentableText.startsWith("Set") || toPresentableText.startsWith("HashSet"))
                && (fromPresentableText.startsWith("Set") || fromPresentableText.startsWith("HashSet"));
    }


    public static boolean isSameSubType(PsiType to, PsiType from) {
        String toPresentableText = to.getCanonicalText();
        String fromPresentableText = from.getCanonicalText();
        String toSubType = toPresentableText.substring(toPresentableText.indexOf("<") + 1, toPresentableText.lastIndexOf(">"));
        String fromSubType = fromPresentableText.substring(fromPresentableText.indexOf("<") + 1, fromPresentableText.lastIndexOf(">"));
        return toSubType.equals(fromSubType);
    }

    public static String getFirstUpperCase(String oldStr) {
        return oldStr.substring(0, 1).toUpperCase() + oldStr.substring(1);
    }

    private PsiMethod getPsiMethod(AnActionEvent e) {
        PsiElement elementAt = getPsiElement(e);
        if (elementAt == null) {
            return null;
        }
        return PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class);
    }

    private PsiElement getPsiElement(AnActionEvent e) {
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        Editor editor = e.getData(PlatformDataKeys.EDITOR);
        if (psiFile == null || editor == null) {
            e.getPresentation().setEnabled(false);
            return null;
        }
        //用来获取当前光标处的PsiElement
        int offset = editor.getCaretModel().getOffset();
        return psiFile.findElementAt(offset);
    }

    public static class GenerateTemplateManager {
        public static void generate(PsiType returnCollectClassType, PsiType paramCollectClassType) {
            if (isList(returnCollectClassType, paramCollectClassType)) {
                ListGenerateTemplate.generate(returnCollectClassType, paramCollectClassType);
            } else if (isSet(returnCollectClassType, paramCollectClassType)) {
                SetGenerateTemplate.generate(returnCollectClassType, paramCollectClassType);
            } else {
                ObjectGenerateTemplate.generate(returnCollectClassType, paramCollectClassType);
            }
        }
    }


    public static class ListGenerateTemplate {

        private final static String RETURN_TEMPLATE = "return sources.stream()" +
                "\n.map(%s::%s)" +
                "\n.collect(java.util.stream.Collectors.toList());\n}\n";

        public final static String EMPTY_LIST_RETURN_TEMPLATE = "if (org.apache.commons.collections.CollectionUtils.isEmpty(sources)) {\nreturn java.util.Collections.emptyList();\n}\n";

        public static void generate(PsiType returnClassType, PsiType paramClassType) {
            String returnClassImportName = returnClassType.getInternalCanonicalText();

            String paramClassImportName = paramClassType.getInternalCanonicalText();

            PsiType returnCollectClassType = getReturnCollectClass((PsiClassReferenceType) returnClassType);

            PsiType paramCollectClassType = getReturnCollectClass((PsiClassReferenceType) paramClassType);

            String returnCollectClassName = returnCollectClassType.getPresentableText();

            String methodName = String.format(COLLECT_METHOD_TEMPLATE, returnClassImportName, returnCollectClassName, paramClassImportName);

            String subMethodName = "convert2" + returnCollectClassName;

            String methodText = methodName + EMPTY_LIST_RETURN_TEMPLATE +
                    String.format(RETURN_TEMPLATE, currentFileClassName, subMethodName);

            GENERATE_METHOD_TEXT_LIST.add(methodText);

            GenerateTemplateManager.generate(returnCollectClassType, paramCollectClassType);
        }
    }


    public static class SetGenerateTemplate {

        private final static String RETURN_TEMPLATE = "return sources.stream()" +
                "\n.map(%s::%s(x))" +
                "\n.collect(java.util.stream.Collectors.toSet());\n}\n";

        public final static String EMPTY_SET_RETURN_TEMPLATE = "if (org.apache.commons.collections.CollectionUtils.isEmpty(sources)) {\nreturn java.util.Collections.emptySet();\n}\n";

        public static void generate(PsiType returnClassType, PsiType paramClassType) {
            String returnClassImportName = returnClassType.getInternalCanonicalText();

            String paramClassImportName = paramClassType.getInternalCanonicalText();

            PsiType returnCollectClassType = getReturnCollectClass((PsiClassReferenceType) returnClassType);

            PsiType paramCollectClassType = getReturnCollectClass((PsiClassReferenceType) paramClassType);

            String returnCollectClassName = returnCollectClassType.getPresentableText();

            String methodName = String.format(COLLECT_METHOD_TEMPLATE, returnClassImportName, returnCollectClassName, paramClassImportName);

            String subMethodName = "convert2" + returnCollectClassName;

            String methodText = methodName + EMPTY_SET_RETURN_TEMPLATE +
                    String.format(RETURN_TEMPLATE, currentFileClassName, subMethodName);

            GENERATE_METHOD_TEXT_LIST.add(methodText);

            GenerateTemplateManager.generate(returnCollectClassType, paramCollectClassType);

        }
    }

    public static class ObjectGenerateTemplate {

        public final static String METHOD_TEMPLATE = "public static %s convert2%s (%s  " + SOURCE_NAME + ") {\n";

        public final static String NULL_OBJECT_RETURN_TEMPLATE = "if (source == null) {\nreturn null;\n}\n";

        public final static String NEW_OBJECT_RETURN_TEMPLATE = "%s " + TARGET_NAME + " = new %s();\n";


        public static void generate(PsiType returnClassType, PsiType paramClassType) {

            String returnClassImportName = returnClassType.getInternalCanonicalText();

            String paramClassImportName = paramClassType.getInternalCanonicalText();

            String returnClassName = ((PsiClassReferenceType) returnClassType).getClassName();

            String methodName = String.format(METHOD_TEMPLATE, returnClassImportName, returnClassName, paramClassImportName);

            String methodText = methodName + NULL_OBJECT_RETURN_TEMPLATE;

            methodText = methodText + String.format(NEW_OBJECT_RETURN_TEMPLATE, returnClassImportName, returnClassImportName);

            PsiClass returnClass = ((PsiClassReferenceType) returnClassType).resolve();

            PsiClass paramClass = ((PsiClassReferenceType) paramClassType).resolve();

            StringBuilder builder = new StringBuilder(methodText);

            for (PsiField field : returnClass.getAllFields()) {
                generateForField(paramClass, builder, field);
            }

            builder.append("return " + TARGET_NAME + ";\n");
            builder.append("}\n");
            GENERATE_METHOD_TEXT_LIST.add(builder.toString());
        }

        private static void generateForField(PsiClass paramClass, StringBuilder builder, PsiField field) {
            PsiModifierList modifierList = field.getModifierList();
            if (modifierList == null || modifierList.hasModifierProperty(PsiModifier.STATIC) || modifierList
                    .hasModifierProperty(PsiModifier.FINAL) || modifierList
                    .hasModifierProperty(PsiModifier.SYNCHRONIZED)) {
                return;
            }

            PsiField paramField = paramClass.findFieldByName(field.getNameIdentifier().getText(), false);
            if (paramField == null) {
                builder.append(TARGET_NAME + ".set" + getFirstUpperCase(field.getNameIdentifier().getText()) + "();\n");
                return;
            }
            PsiType paramFieldType = paramField.getType();
            PsiType returnFieldType = field.getType();

            if (isListOrSet(returnFieldType, paramFieldType)) {
                generateForCollectField(builder, field, paramFieldType, returnFieldType);
            } else if (isDiffObjAndNotPrimitiveType(returnFieldType, paramFieldType)) {
                generateForObjectField(builder, field, paramField, paramFieldType, returnFieldType);
            } else {
                builder.append(TARGET_NAME + ".set" + getFirstUpperCase(field.getNameIdentifier().getText()) + "(source.get"
                        + getFirstUpperCase(field.getNameIdentifier().getText()) + "());\n");
            }
        }

        private static void generateForObjectField(StringBuilder builder, PsiField field, PsiField paramField, PsiType paramFieldType, PsiType returnFieldType) {
            String getMethodName = "source.get" + getFirstUpperCase(paramField.getNameIdentifier().getText()) + "()";
            builder.append(TARGET_NAME + ".set" + getFirstUpperCase(field.getNameIdentifier().getText()) + "(convert2" + ((PsiClassReferenceType) returnFieldType).getClassName() + "(" + getMethodName + "));\n");
            GenerateTemplateManager.generate(returnFieldType, paramFieldType);
        }

        private static void generateForCollectField(StringBuilder builder, PsiField field, PsiType paramFieldType, PsiType returnFieldType) {
            PsiType returnCollectClassType = getReturnCollectClass(((PsiClassReferenceType) returnFieldType));
            PsiType paramCollectClassType = getReturnCollectClass(((PsiClassReferenceType) paramFieldType));

            if (isSameSubType(returnFieldType, paramFieldType)) {
                builder.append(TARGET_NAME + ".set" + getFirstUpperCase(field.getNameIdentifier().getText()) + "(source.get"
                        + getFirstUpperCase(field.getNameIdentifier().getText()) + "());\n");
            } else {
                builder.append(TARGET_NAME + ".set" + getFirstUpperCase(field.getNameIdentifier().getText()) + "(convert2" + returnCollectClassType.getPresentableText() + "s));\n");
            }
            GenerateTemplateManager.generate(returnCollectClassType, paramCollectClassType);
        }
    }


    private static PsiType getReturnCollectClass(PsiClassReferenceType returnClassType) {
        return returnClassType.getReference().getTypeParameters()[0];
    }

}
