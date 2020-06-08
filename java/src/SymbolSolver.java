import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.TypeParameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.json.simple.parser.JSONParser;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A class that allows to colve most type identifiers based on imports and program structure
 */
public class SymbolSolver {
    private static final String TYPE_PARAM = "__T__";
    private static final HashSet<String> javaLang; // list of classes in java.lang package
    private static final HashMap<String, HashSet<String>> repoToPackage; // for each repository,
    // this gives a list of packages defined in that repository
    public static final ClassIndex globalClassIndex; // global classIndex constructed for all repos

    static { // initializing the static constants
        globalClassIndex = new ClassIndex();
        globalClassIndex.fromJson(ClassIndex.parseJSON(new JSONParser(), "../data/classIndex.json", true));
        repoToPackage = globalClassIndex.getRepoToPackage();
        javaLang = new HashSet<>(Arrays.asList("Appendable", "AutoCloseable",
                "CharSequence", "Cloneable", "Comparable", "Iterable", "Readable", "Runnable",
                "Boolean", "Byte", "Character", "Class", "ClassLoader", "ClassValue", "Compiler",
                "Double", "Enum", "Float", "InheritableThreadLocal", "Integer", "Long", "Math",
                "Number", "Object", "Package", "Process", "ProcessBuilder", "Runtime",
                "RuntimePermission", "SecurityManager", "Short", "StackTraceElement", "StrictMath",
                "String", "StringBuffer", "StringBuilder", "System", "Thread", "ThreadGroup",
                "ThreadLocal", "Throwable", "Void", "ArithmeticException",
                "ArrayIndexOutOfBoundsException", "ArrayStoreException", "ClassCastException",
                "ClassNotFoundException", "CloneNotSupportedException",
                "EnumConstantNotPresentException", "Exception", "IllegalAccessException",
                "IllegalArgumentException", "IllegalMonitorStateException", "IllegalStateException",
                "IllegalThreadStateException", "IndexOutOfBoundsException",
                "InstantiationException", "InterruptedException", "NegativeArraySizeException",
                "NoSuchFieldException", "NoSuchMethodException", "NullPointerException",
                "NumberFormatException", "ReflectiveOperationException", "RuntimeException",
                "SecurityException", "StringIndexOutOfBoundsException", "TypeNotPresentException",
                "UnsupportedOperationException", "AbstractMethodError", "AssertionError",
                "BootstrapMethodError", "ClassCircularityError", "ClassFormatError", "Error",
                "ExceptionInInitializerError", "IllegalAccessError", "IncompatibleClassChangeError",
                "InstantiationError", "InternalError", "LinkageError", "NoClassDefFoundError",
                "NoSuchFieldError", "NoSuchMethodError", "OutOfMemoryError", "StackOverflowError",
                "ThreadDeath", "UnknownError", "UnsatisfiedLinkError",
                "UnsupportedClassVersionError", "VerifyError", "VirtualMachineError", "Deprecated",
                "Override", "SafeVarargs", "SuppressWarnings"));
    }

    private final String repo;  // the repository in which the solver is used
    private HashSet<String> knownWildcardImports; // list of wildcard imports from globalClassIndex
    private HashMap<String, String> localClassIndex; // classes imported explicitly
    private String unknownWildcardPackageImport; // iff there is a single unknown pack* import
    private HashMap<String, HashSet<String>> classTypeParameters; // class-level type parameters
    private HashMap<String, HashSet<String>> methodTypeParameters; // method-level type parameters

    public SymbolSolver(String repo, CompilationUnit ast) {
        this.repo = repo;
        this.unknownWildcardPackageImport = null;
        this.knownWildcardImports = new HashSet<>();
        this.localClassIndex = new HashMap<>();
        this.classTypeParameters = new HashMap<>();
        this.methodTypeParameters = new HashMap<>();

        DataCollector visitor = new DataCollector();
        visitor.visit(ast, this);

        if ((this.unknownWildcardPackageImport != null) &&
                (this.unknownWildcardPackageImport.equals("")))
            this.unknownWildcardPackageImport = null;
    }

    public String solve(String type, String className, String methodName) {
        // System.out.println(type);
        Pattern pattern = Pattern.compile("[A-Za-z0-9\\.]*");
        Matcher m = pattern.matcher(type);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String group = m.group();
            if (!group.equals("")) {
                String replacement = solveSingleType(group, className, methodName);
                // System.out.println(group + " " + replacement);
                if (replacement == null)
                    return null;
                else {
                    try {
                        m.appendReplacement(sb, replacement);
                    } catch (IllegalArgumentException e) {
                        // happens then a class name contains a $ sign
                        return null;
                    }
                }
            }
        }
        m.appendTail(sb);
        // System.out.println(type+ " " + sb);
        return String.valueOf(sb);
    }


    public String solveSingleType(String type, String className, String methodName) {
        if (Character.isLowerCase(type.charAt(0)))
            return type; // either already a fully qualified name or a primitive class
        if ((methodTypeParameters.getOrDefault(className + "." + methodName, new HashSet<>()).contains(type)) ||
                (classTypeParameters.getOrDefault(className, new HashSet<>()).contains(type)))
            return TYPE_PARAM;

        if (!type.contains(".")) { // public class or interface
            if (localClassIndex.containsKey(type)) // directly imported
                return localClassIndex.get(type) + "." + type;
            if (javaLang.contains(type)) // a java.lang class
                return "java.lang." + type;
            // from a known wildcard import from within the repository
            if (globalClassIndex.classToPackToRepo.containsKey(type)) {
                for (String pack:globalClassIndex.classToPackToRepo.get(type).keySet())
                    if (knownWildcardImports.contains(pack) && globalClassIndex.classToPackToRepo.
                            get(type).get(pack).contains(repo))
                        return pack + "." + type;
            } else if (unknownWildcardPackageImport != null) // from the unknown wildcard import
                return unknownWildcardPackageImport + "." + type;
            return null;
        }

        // if program execution reaches this point it must be the case that
        // the class in question is not public
        String publicClassName = type.split("\\.")[0];
        if (localClassIndex.containsKey(publicClassName))
            return localClassIndex + "." + type;
        // TODO what about wildcard imports here
        return null;
    }


    /**
     * A visitor class that fills the classToPackToRepo dictionary
     */
    class DataCollector extends VoidVisitorAdapter<SymbolSolver> {

        private String currectClass = "";
        private String currentPackage = "";

        /**
         * Record a package declaration
         * @param id   the node in the AST
         * @param data symbolSolver object to record the package name to
         */
        @Override
        public void visit(PackageDeclaration id, SymbolSolver data) {
            data.knownWildcardImports.add(id.getNameAsString());
            currentPackage = id.getNameAsString();
            super.visit(id, data);
        }

        @Override
        public void visit(ImportDeclaration id, SymbolSolver data) {
            super.visit(id, data);
            String name = id.getNameAsString();
            if (!name.contains("."))
                return;  // import must be fully qualified
            if (id.isAsterisk()) {
                String[] parts = name.split("\\.");
                if (Character.isUpperCase(parts[parts.length - 1].charAt(0))) // class * import
                    return; // TODO - class wildcard imports
                if (SymbolSolver.repoToPackage.get(data.repo).contains(name))
                    knownWildcardImports.add(name);
                else {
                    if (unknownWildcardPackageImport == null)
                        unknownWildcardPackageImport = name;
                    else
                        unknownWildcardPackageImport = "";
                }
            } else {
                String[] path = name.split("\\.");
                String className = path[path.length - 1];
                localClassIndex.put(className, name.substring(0, name.length() - className.length() - 1));
            }
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration id, SymbolSolver data) {
            if (currectClass.equals("")) {
                data.localClassIndex.put(id.getNameAsString(), currentPackage);
                currectClass = id.getNameAsString();
            } else {
                data.localClassIndex.put(id.getNameAsString(), currentPackage + "." + currectClass);
                currectClass += "." + id.getNameAsString();
            }
            classTypeParameters.putIfAbsent(currentPackage + "." + currectClass, new HashSet<>());
            for (TypeParameter param: id.getTypeParameters())
                classTypeParameters.get(currentPackage + "." + currectClass).add(param.getName().asString());
            super.visit(id, data);
            if (currectClass.equals(id.getNameAsString()))
                currectClass = "";
            else
                currectClass = currectClass.substring(0, currectClass.length() - id.getNameAsString().length() - 1);
        }

        @Override
        public void visit(MethodDeclaration id, SymbolSolver data) {
            super.visit(id, data);
            String methodName = currentPackage + "." + currectClass + "." + id.getNameAsString();
            methodTypeParameters.putIfAbsent(methodName, new HashSet<>());
            for (TypeParameter param: id.getTypeParameters())
                methodTypeParameters.get(methodName).add(param.getName().asString());
        }
    }

}
