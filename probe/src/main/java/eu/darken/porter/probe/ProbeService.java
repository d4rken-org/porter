package eu.darken.porter.probe;

import android.os.Process;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class ProbeService extends IProbe.Stub {
    @Override public void destroy() { System.exit(0); }

    @Override public int uid() { return Process.myUid(); }

    @Override public String readFile(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (IOException e) {
            return e.toString();
        }
    }
}
