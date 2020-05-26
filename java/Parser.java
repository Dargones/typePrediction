import com.github.javaparser.JavaParser;
import org.json.simple.JSONObject;

public interface Parser {
    JSONObject extractImports(JSONObject obj, JavaParser javaParser);
}
