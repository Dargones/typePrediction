import com.github.javaparser.JavaParser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Paths;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * A class that provides functionality for extracting relevant information from java source code
 * downloaded from BigQuery
 */
public abstract class Parser {

    static final HashSet<String> validRepos = SymbolSolver.globalClassIndex.getValidRepos();
    static long methodsTotal;
    static long methodsWithDocs;
    static long solvedMethodsWithDocs;

    public static void main(String[] args) {
        String dirIn =  "/Volumes/My Passport/import_prediction/data/GitHubOriginal";
        String dirOut = "/Volumes/My Passport/typePrediction/data/Parsed2";
        for (String fileName: new File(dirIn).list())
            processBigQueryFile(dirIn + "/" + fileName, dirOut + "/" + fileName);
    }

    /**
     * Parse a single .json file and extract from it the relevant information
     * @param inputFile
     * @param outputFile
     * @return
     */
    public static void processBigQueryFile(String inputFile, String outputFile) {
        JSONParser jsonParser = new JSONParser();
        JavaParser javaParser = new JavaParser();
        List<JSONObject> lines = null;
        System.out.println("Processing " + inputFile + "...");

        try {
            lines = Files.lines(Paths.get(inputFile))
                    .map(file -> parseJSON(jsonParser, file))
                    .map(file -> extractData(file, javaParser))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()); // get the list of files
        } catch (IOException e) {
            e.printStackTrace();
        }

        saveJSON(lines, outputFile);
        System.out.println(methodsTotal + " " + methodsWithDocs + " " + solvedMethodsWithDocs);
    }


    /***
     * A wrapper around jsonParser.parse with exception caught (so that it can be used in a stream)
     * @param jsonParser A JSONParser
     * @param line       A Json object (a line in the file which is read)
     * @return
     */
    public static JSONObject parseJSON(JSONParser jsonParser, String line) {
        try {
            return (JSONObject) jsonParser.parse(line);
        } catch (ParseException e) {
            return null;
        }
    }


    /**
     * Saves the json data back to a file
     * @param objects
     * @param outputFile
     */
    public static void saveJSON(List<JSONObject> objects, String outputFile) {
        try (FileWriter file = new FileWriter(outputFile)) {
            for (JSONObject obj: objects)
                file.write(obj.toString() + '\n');
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Initializes a json object to write the data extracted from a single Java file to.
     * @param obj  the object from the original .json file (unparsed)
     * @return     the object that can be filled with parse information
     */
    public static JSONObject constructEmpyTable(JSONObject obj) {
        JSONObject data = new JSONObject();
        data.put("repo", obj.get("repo_name")); // name of the repository associated with this file
        data.put("classes", new JSONObject());
        return data;
    }


    /**
     * Use Java Parser to extract relevant information from a single Java file
     * @param obj         Java file as a json object (one of many in a .json file)
     * @param javaParser  the Parser object to use
     * @return
     */
    public static JSONObject extractData(JSONObject obj, JavaParser javaParser) {
        JSONObject data = constructEmpyTable(obj);
        if (!validRepos.contains((String) data.get("repo")))
            return null;

        if (obj.get("content") == null) {  // if the file contains no code, do not attempt parsing
            System.out.println("File is empty");
            return null;
        }

        CompilationUnit ast;
        try {
            ast = javaParser.parse((String) obj.get("content"));
        } catch (Exception e) {
            System.out.println("Bad Java parse error"); // if parsing failed, return an empty object
            return data;
        } catch (AssertionError e) {
            System.out.println("Very bad Java parse error");
            return data;
        }

        SymbolSolver symbolSolver = new SymbolSolver((String) data.get("repo"), ast);

        DataCollector visitor = new DataCollector(symbolSolver);
        visitor.visit(ast, data);
        if (((JSONObject) data.get("classes")).isEmpty())
            return null;
        return data;
    }

    /**
     * A visitor class that fills a json object with information extracted from teh AST
     */
    static class DataCollector extends VoidVisitorAdapter<JSONObject> {

        SymbolSolver solver;
        private String currectClass = "";
        private JSONObject classObject = new JSONObject();

        DataCollector(SymbolSolver solver) {
            this.solver = solver;
        }

        /**
         * Make sure that the name of the main class\interface in the compilation unit matches
         * the name of the file. Also record any information about extended\implemented classes or
         * interfaces
         * @param id   the node in the AST
         * @param data json object to record the information to
         */
        @Override
        public void visit(ClassOrInterfaceDeclaration id, JSONObject data) {
            classObject = new JSONObject();
            String previousClass = currectClass;
            currectClass = solver.solve(id.getNameAsString(), "", "");
            if (currectClass == null)
                return;
            super.visit(id, data);
            if (!classObject.isEmpty())
                ((JSONObject) data.get("classes")).put(currectClass, classObject);
            currectClass = previousClass;
        }


        public void visit(MethodDeclaration id, JSONObject data) {
            Parser.methodsTotal++;
            Boolean hasDocStrings = false;
            Boolean allTypesSolved = true;
            JSONObject methodObject = new JSONObject();

            if (!id.getJavadoc().isPresent())
                return;
            Javadoc javadoc = id.getJavadoc().get();

            JSONObject returnObject = new JSONObject();
            String solvedType = solver.solve(id.getType().asString(), currectClass, id.getNameAsString());
            if (solvedType == null) {
                allTypesSolved = false;
                solvedType = "";
            }
            returnObject.put("type", solvedType);
            String returnDocString = getReturnDocString(javadoc);
            if (!returnDocString.equals("")) {
                returnObject.put("doc", returnDocString);
                hasDocStrings = true;
            }
            methodObject.put("return", returnObject);

            JSONObject parametersObject = new JSONObject();
            for (Parameter param: id.getParameters()) {
                JSONObject paramObject = new JSONObject();
                solvedType = solver.solve(param.getType().asString(), currectClass, id.getNameAsString());
                if (solvedType == null) {
                    allTypesSolved = false;
                    solvedType = "";
                }
                paramObject.put("type", solvedType);
                String paramDocString = getParamDocString(javadoc, param.getNameAsString());
                if (!paramDocString.equals("")) {
                    paramObject.put("doc", paramDocString);
                    hasDocStrings = true;
                }
                parametersObject.put(param.getNameAsString(), paramObject);
            }
            methodObject.put("params", parametersObject);

            String methodDocString = getMethodDocString(javadoc);
            if (!methodDocString.equals(""))
                hasDocStrings = true;
            methodObject.put("docstring", methodDocString);

            if (!id.getTokenRange().isPresent())
                return;
            methodObject.put("source", id.getTokenRange().get().toString());

            if (hasDocStrings)
                Parser.methodsWithDocs++;
            if (hasDocStrings && allTypesSolved) {
                Parser.solvedMethodsWithDocs++;
                classObject.put(id.getNameAsString(), methodObject);
            }
        }

        private String getMethodDocString(Javadoc javadoc) {
            return javadoc.getDescription().toText();
        }

        private String getParamDocString(Javadoc javadoc, String paramName) {
            for (JavadocBlockTag block:javadoc.getBlockTags())
                if ((block.getType() ==  JavadocBlockTag.Type.PARAM) &&
                        (block.getName().isPresent()) &&
                        (block.getName().get().equals(paramName)))
                    return block.getContent().toText();
            return "";
        }

        private String getReturnDocString(Javadoc javadoc) {
            for (JavadocBlockTag block:javadoc.getBlockTags())
                if (block.getType() ==  JavadocBlockTag.Type.RETURN)
                    return block.getContent().toText();
            return "";
        }

    }
}
