package util;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Error {
    private ArrayList<Integer> lines = new ArrayList<>();
    private ArrayList<String> kinds = new ArrayList<>();
    private Tool tool = new Tool();

    public void addError(String kind, int line) {
        lines.add(line);
        kinds.add(kind);
    }

    public Boolean isError() {
        return !kinds.isEmpty();
    }

    public void printError() throws FileNotFoundException {
        tool.setOutput("error.txt");
        for (int i = 0; i < lines.size(); i++) {
            System.out.println(lines.get(i) + " " + kinds.get(i));
        }
    }
}
