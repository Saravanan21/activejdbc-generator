package com.saravanan.activejdbc.processor;

import com.saravanan.activejdbc.annotation.ModelAnnotation;
import com.squareup.javapoet.*;
import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.FileInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelProcessor extends AbstractProcessor {
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton(ModelAnnotation.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(ModelAnnotation.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                /*
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        String.format("Only classes can be annotated with @%s", ModelAnnotation.class.getSimpleName()),
                        element
                );
                */
                continue;
            }
            TypeElement typeElement = (TypeElement) element;
            if (processAnnotation(typeElement)) {
                return true;
            }
        }
        return false;
    }

    public boolean processAnnotation(TypeElement element) {
        try {
            final String annotatedClassName = element.getQualifiedName().toString();
            System.out.println("Processing: " + annotatedClassName);
            final String regexString = "\\.([^\\.]*)$";
            Matcher matcher = Pattern.compile(regexString).matcher(annotatedClassName);
            if (!matcher.find()) {
                System.out.println("Error processing, class not in a package");
                return true;
            }
            final String generatedClassName = "Model" + matcher.group(1);
            System.out.println("Generating: " + generatedClassName);
            final String fullGeneratedClassName = annotatedClassName.replaceFirst(regexString, "");
            System.out.println("Generating in package: " + fullGeneratedClassName);

            Properties connectionProps = new Properties();
            ModelAnnotation annotation = element.getAnnotation(ModelAnnotation.class);
            System.out.println("Load from: " + annotation.propsPath());
            connectionProps.load(new FileInputStream(annotation.propsPath()));
            if (connectionProps.containsKey("db.driver")) {
                Class.forName(connectionProps.getProperty("db.driver"));
            }
            Connection connection = DriverManager.getConnection(
                    connectionProps.getProperty("db.url"),
                    connectionProps.getProperty("db.username"),
                    connectionProps.getProperty("db.password")
            );

            Table tableAnnotation = element.getAnnotation(Table.class);
            String tableName = tableAnnotation == null ? element.getSimpleName().toString() : tableAnnotation.value();
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("select * from " + tableName);
            ResultSetMetaData rsmd = rs.getMetaData();
            List<MethodSpec> methodSpecs = new LinkedList<>();
            for (int i=1; i<=rsmd.getColumnCount(); i++) {
                String columnName = rsmd.getColumnName(i);
                Class columnClass = getColumnClass(rsmd.getColumnType(i));
                String typedMethodName = getColumnTypeMethodName(rsmd.getColumnType(i));

                MethodSpec getMethod = MethodSpec.methodBuilder("get" + toTitleCase(columnName))
                        .addModifiers(Modifier.PUBLIC)
                        .returns(columnClass)
                        .addStatement("return get$L($S)", typedMethodName, columnName)
                        .build();
                methodSpecs.add(getMethod);

                String parameterName = toCamelCase(columnName);
                MethodSpec setMethod = MethodSpec.methodBuilder("set" + toTitleCase(columnName))
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(columnClass, parameterName)
                        .returns(void.class)
                        .addStatement("set$L($S, $L)", typedMethodName, columnName, parameterName)
                        .build();
                methodSpecs.add(setMethod);
            }

            TypeSpec helloWorld = TypeSpec.classBuilder(generatedClassName)
                    .superclass(Model.class)
                    .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                    .addMethods(methodSpecs)
                    .build();

            JavaFile javaFile = JavaFile.builder(fullGeneratedClassName, helloWorld)
                    .build();

            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            System.out.println("Failed: " + e.toString());
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write annotation to file on: " + element.toString());
            return true;
        } catch (Exception e) {
            System.out.println("Failed: " + e.toString());
            e.printStackTrace();
            return true;
        }
        return false;
    }

    private static Class getColumnClass(int columnType) {
        switch (columnType) {
            case Types.VARCHAR:
                return String.class;
            case Types.DATE:
                return Date.class;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return Time.class;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return Timestamp.class;
            case Types.INTEGER:
                return Integer.class;
            case Types.DECIMAL:
            case Types.DOUBLE:
                return Double.class;
            default:
                return Object.class;
        }
    }

    private static String getColumnTypeMethodName(int columnType) {
        switch (columnType) {
            case Types.VARCHAR:
                return "String";
            case Types.DATE:
                return "Date";
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return "Time";
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return "Timestamp";
            case Types.INTEGER:
                return "Integer";
            case Types.DECIMAL:
            case Types.DOUBLE:
                return "Double";
            default:
                return "";
        }
    }

    private static String toTitleCase(String s) {
        return capitalize(toCamelCase(s));
    }

    private static String toCamelCase(String s) {
        String[] parts = s.split("_");
        StringBuilder camelCaseString = new StringBuilder();
        for (String part : parts){
            camelCaseString.append(capitalize(part));
        }
        return capitalize(camelCaseString.toString());
    }

    private static String capitalize(String original) {
        if (original == null || original.length() == 0) {
            return original;
        }
        return original.substring(0, 1).toUpperCase() + original.substring(1);
    }
}
