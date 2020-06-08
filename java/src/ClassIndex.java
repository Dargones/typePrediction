import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * ClassIndex can store info about all the classes defined in the BigQuery dataset and contains
 * methods that allow gathering this info
 */
public class ClassIndex {
    HashMap<String, HashMap<String, Set<String>>> classToPackToRepo;
    // maps from a simple class name and a package name to a list of repositories in which a class
    // with this name is defined in a package with this name

    public ClassIndex() {
        classToPackToRepo = new HashMap<>();
    }

    public static void main(String[] args) {
        ClassIndex classIndex;
        String jsonFileDir = "/Volumes/My Passport/import_prediction/data/GitHubOriginal";
        for (String fileName: new File(jsonFileDir).list()) {
            classIndex = new ClassIndex();
            classIndex.addBigQueryData(jsonFileDir + "/" + fileName);
            saveJson(classIndex.toJson(), "../data/indices/" + fileName + ".json");
        }

        classIndex = new ClassIndex();
        for (String fileName: new File(jsonFileDir).list()) {
            System.out.println("Read " + fileName + "...");
            classIndex.fromJson(parseJSON(new JSONParser(),"../data/indices/" + fileName + ".json", true));
        }

        System.out.println("Saving all");
        saveJson(classIndex.toJson(), "../data/classIndex.json");
    }


    /**
     * Convert the data stored in a json object to a hashmap
     * @param json a JSONObject
     */
    public void fromJson(JSONObject json) {
        for (Object clazz_key: json.keySet()) {
            String clazz = (String) clazz_key;
            classToPackToRepo.putIfAbsent(clazz, new HashMap<>());
            for (Object pack_key: ((JSONObject) json.get(clazz_key)).keySet()) {
                String pack = (String) pack_key;
                classToPackToRepo.get(clazz).putIfAbsent(pack, new HashSet<>());
                for (Object repo: (JSONArray)((JSONObject) json.get(clazz_key)).get(pack_key))
                    classToPackToRepo.get(clazz).get(pack).add((String) repo);
            }
        }
    }

    /**
     * Convert the data stored in the index to a json object that could be written to a file
     * @return return a json object
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        for (String clazz: classToPackToRepo.keySet()) {
            JSONObject packages = new JSONObject();
            for (String pack: classToPackToRepo.get(clazz).keySet()) {
                JSONArray repos = new JSONArray();
                for (String repo : classToPackToRepo.get(clazz).get(pack))
                    repos.add(repo);
                packages.put(pack, repos);
            }
            json.put(clazz, packages);
        }
        return json;
    }

    /**
     * Write a json Object to a file
     * @param jsonObject
     * @param fileName
     */
    public static void saveJson(JSONObject jsonObject, String fileName) {
        try (FileWriter file = new FileWriter(fileName)) {
            String toWrite = "{";
            for (Object key: jsonObject.keySet()) {
                file.write(toWrite);
                toWrite = "\"" + key + "\":" +
                        ((JSONObject) jsonObject.get(key)).toJSONString() + ",";
            }
            file.write(toWrite.substring(0, toWrite.length() - 1) + "}");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /***
     * A wrapper around jsonParser.parse with exception caught (so that it can be used in a stream)
     * @param jsonParser A JSONParser
     * @param line       A Json object (a line in the file which is read)
     * @param isFileName If True, treat line as the name of the file to be parsed
     * @return
     */
    public static JSONObject parseJSON(JSONParser jsonParser, String line, boolean isFileName) {
        try {
            if (isFileName)
                return (JSONObject) jsonParser.parse(new FileReader(new File(line)));
            return (JSONObject) jsonParser.parse(line);
        } catch (Exception e) {
            System.out.println(e.getStackTrace());
            return null;
        }
    }

    /**
     * Return a dictionary that for each repository specifies a set of packages defined in that
     * repository
     * @return
     */
    public HashMap<String, HashSet<String>> getRepoToPackage() {
        HashMap<String, HashSet<String>> repoToPackage = new HashMap<>();
        for (String clazz: classToPackToRepo.keySet())
            for (String pack : classToPackToRepo.get(clazz).keySet())
                for (String repo : classToPackToRepo.get(clazz).get(pack)) {
                    repoToPackage.putIfAbsent(repo, new HashSet<>());
                    repoToPackage.get(repo).add(pack);
                }
        return repoToPackage;
    }


    /**
     * Get a set of repositories without duplicates (i.e. such that each fully-qualified class
     * name definition is unique to some repository) that also have a package declaration in
     * every file.
     * @return
     */
    public HashSet<String> getValidRepos() {
        HashMap<String, Integer> repoSizes = getRepoSizes();
        HashSet<String> result = new HashSet<>();
        for (String repo: repoSizes.keySet())
            result.add(repo);

        for (String clazz: classToPackToRepo.keySet())
            for (String pack : classToPackToRepo.get(clazz).keySet()) {
                if (pack.equals("")) {
                    for (String repo : classToPackToRepo.get(clazz).get(pack))
                        result.remove(repo);
                    continue;
                }
                int maxSize = 0; // the size of the largest repo in which this class is defined
                String maxRepo = ""; // the name of that repo
                for (String repo : classToPackToRepo.get(clazz).get(pack))
                    if (result.contains(repo)) {
                        if (repoSizes.get(repo) > maxSize) {
                            result.remove(maxRepo);
                            maxSize = repoSizes.get(repo);
                            maxRepo = repo;
                        } else {
                            result.remove(repo);
                        }
                    }
            }

        return result;
    }

    /**
     * Get a dictionary that for each repository specifies the number of classes defined in that
     * repository
     * @return
     */
    public HashMap<String, Integer> getRepoSizes() {
        HashMap<String, Integer> repoSizes = new HashMap<>();
        for (HashMap<String, Set<String>> clazz: classToPackToRepo.values())
            for (Set<String> packages: clazz.values())
                for (String repo: packages)
                    repoSizes.put(repo, repoSizes.getOrDefault(repo, 0) + 1);
        return repoSizes;
    }

    /** Add all information about classes that can be gathered from one of the files downloaded
     * from BigQuery to this RepositoryIndex
     * @param jsonFileName the name of the file with code from BigQuery
     */
    public void addBigQueryData(String jsonFileName) {
        JSONParser jsonParser = new JSONParser();
        JavaParser javaParser = new JavaParser();
        System.out.println("Processing " + jsonFileName + "...");
        try {
            Files.lines(Paths.get(jsonFileName))
                    .map(file -> parseJSON(jsonParser, file, false))
                    .forEach(file -> extractData(file, javaParser));// get the list of fi
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void extractData(JSONObject file, JavaParser parser) {
        if (file.get("content") == null) {  // if the file contains no code, do not attempt parsing
            System.out.println("File is empty");
            return;
        }

        CompilationUnit ast;
        try {
            ast = parser.parse((String) file.get("content"));
        } catch (Exception e) {
            System.out.println("Bad Java parse error"); // if parsing failed, return an empty object
            return;
        } catch (AssertionError e) {
            System.out.println("Very bad Java parse error");
            return;
        }

        String[] filePath = file.get("path").toString().split("/");
        String fileName = filePath[filePath.length -1].split("\\.")[0];

        DataCollector visitor = new DataCollector((String) file.get("repo_name"), fileName);
        visitor.visit(ast, this);
    }

    /**
     * A visitor class that fills the classToPackToRepo dictionary
     */
    class DataCollector extends VoidVisitorAdapter<ClassIndex> {

        private String packageName;
        private String repoName;
        private String fileName;

        DataCollector(String repoName, String fileName) {
            packageName = "";
            this.repoName = repoName;
            this.fileName = fileName;
        }

        /**
         * Record a package declaration
         * @param id   the node in the AST
         * @param data classIndex object to record the package name to
         */
        @Override
        public void visit(PackageDeclaration id, ClassIndex data) {
            packageName = id.getNameAsString();
            super.visit(id, data);
        }

        /**
         * Record a class declaration
         * @param id   the node in the AST
         * @param data data classIndex object to record the package name to
         */
        @Override
        public void visit(ClassOrInterfaceDeclaration id, ClassIndex data) {
            String clazzName = id.getNameAsString();
            if (!clazzName.equals(fileName))
                clazzName = fileName + "." + clazzName; // TODO: fix this for super-nested classes
            data.classToPackToRepo.putIfAbsent(clazzName, new HashMap<>());
            data.classToPackToRepo.get(clazzName).putIfAbsent(this.packageName, new HashSet<>());
            data.classToPackToRepo.get(clazzName).get(packageName).add(this.repoName);
        }
    }
}
