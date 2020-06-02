import com.github.javaparser.JavaParser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * A class that provides functionality for extracting relevant information about imports,
 * packages, etc. from .json files with Java code downloaded from BigQuery
 */
public abstract class Main {

    public static void main(String[] args) {
        String dirIn =  "/Volumes/My Passport/import_prediction/data/GitHubOriginal";
        String dirOut = "/Volumes/My Passport/typePrediction/data/Parsed2";
        for (String fileName: new File(dirIn).list())
            processDataset(dirIn + "/" + fileName, dirOut + "/" + fileName);
    }

    /**
     * Parse a single .json file and extract from it the relevant information
     * @param inputFile
     * @param outputFile
     */
    public static void processDataset(String inputFile, String outputFile) {
        JSONParser jsonParser = new JSONParser();
        JavaParser javaParser = new JavaParser();
        List<JSONObject> lines = null;
        Parser parser = new TuftsParser();

        try {
            lines = Files.lines(Paths.get(inputFile))
                    .map(file -> parseJSON(jsonParser, file))
                    .map(file -> parser.extractData(file, javaParser))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()); // get the list of files
        } catch (IOException e) {
            e.printStackTrace();
        }

        saveJSON(lines, outputFile);
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
}
