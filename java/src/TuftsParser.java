import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.javadoc.Javadoc;
import com.github.javaparser.javadoc.JavadocBlockTag;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class TuftsParser implements Parser {

    /**
     * Initializes a json object to write the data extracted from a single Java file to.
     * @param obj  the object from the original .json file (unparsed)
     * @return     the object that can be filled with parse information
     */
    public JSONObject constructEmpyTable(JSONObject obj) {
        String[] path = obj.get("path").toString().split("/");
        String name = path[path.length -1].split("\\.")[0];

        JSONObject data = new JSONObject();
        data.put("repo", obj.get("repo_name")); // name of the repository associated with this file
        data.put("classes", new JSONObject());
        data.put("currentClass", new JSONObject());
        data.put("classImports", new JSONArray());
        data.put("wildcardImports", new JSONArray());
        data.put("declaredClasses", new JSONArray());
        data.put("package", "");
        data.put("filename", name);
        return data;
    }


    /**
     * Use Java Parser to extract relevant information from a single Java file
     * @param obj         Java file as a json object (one of many in a .json file)
     * @param javaParser  the Parser object to use
     * @return
     */
    public JSONObject extractData(JSONObject obj, JavaParser javaParser) {
        JSONObject data = constructEmpyTable(obj);

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
        }

        TuftsParser.DataCollector visitor = new TuftsParser.DataCollector();
        visitor.visit(ast, data);
        data.remove("currentClass");
        if (data.get("package").equals(""))
            return null;
        return data;
    }

    /**
     * A visitor class that fills a json object with information extracted from teh AST
     */
    static class DataCollector extends VoidVisitorAdapter<JSONObject> {

        /**
         * Record a package declaration
         * @param id   the node in the AST
         * @param data json object to record the package name to
         */
        @Override
        public void visit(PackageDeclaration id, JSONObject data) {
            if (id.getNameAsString().equals(""))
                return;
            data.put("package", id.getNameAsString());
            super.visit(id, data);
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
            JSONObject classObject = new JSONObject();
            data.put("currentClass", classObject);
            super.visit(id, data);

            String fullName = getFullyQualifiedName(id.getNameAsString(), data);
            ((JSONArray) data.get("declaredClasses")).add(fullName);

            if (!classObject.isEmpty())
                ((JSONObject) data.get("classes")).put(fullName, classObject);
        }

        public String getFullyQualifiedName(String className, JSONObject data) {
            if (className.equals(data.get("filename")))
                return data.get("package") + "." + className;
            return data.get("package") + "." + data.get("filename") + "." + className;
        }

        public void visit(MethodDeclaration id, JSONObject data) {
            Boolean hasDocStrings = false;
            JSONObject classObject = (JSONObject) data.get("currentClass");
            JSONObject methodObject = new JSONObject();

            if (!id.getJavadoc().isPresent())
                return;
            Javadoc javadoc = id.getJavadoc().get();

            JSONObject returnObject = new JSONObject();
            returnObject.put("type", id.getType().asString());
            String returnDocString = getReturnDocString(javadoc);
            if (!returnDocString.equals("")) {
                returnObject.put("doc", returnDocString);
                hasDocStrings = true;
            }
            methodObject.put("return", returnObject);

            JSONObject parametersObject = new JSONObject();
            for (Parameter param: id.getParameters()) {
                JSONObject paramObject = new JSONObject();
                paramObject.put("type", param.getType().asString());
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
                classObject.put(id.getNameAsString(), methodObject);
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

        @Override
        public void visit(ImportDeclaration id, JSONObject data) {
            super.visit(id, data);
            String name = id.getNameAsString();
            if (id.isAsterisk()) {
                String[] parts = name.split("\\.");
                if (Character.isUpperCase(parts[parts.length - 1].charAt(0))) // class * import
                    return;
                ((JSONArray) data.get("wildcardImports")).add(name);
            } else
                ((JSONArray) data.get("classImports")).add(name);
        }

    }
}
