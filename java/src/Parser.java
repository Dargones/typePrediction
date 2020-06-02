import com.github.javaparser.JavaParser;
import org.json.simple.JSONObject;

public interface Parser {
    JSONObject extractData(JSONObject obj, JavaParser javaParser);
}
