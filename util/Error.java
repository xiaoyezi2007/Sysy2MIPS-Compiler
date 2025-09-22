package util;

import java.util.ArrayList;

public class Error {
    ArrayList<Integer> lines = new ArrayList<>();
    ArrayList<String> kinds = new ArrayList<>();

    public void addError(String kind, int line) {
        lines.add(line);
        kinds.add(kind);
    }

    public Boolean isError() {
        return !kinds.isEmpty();
    }

    public void printError() {
        for (int i = 0; i < lines.size(); i++) {
            System.out.println(lines.get(i) + " " + kinds.get(i));
        }
    }
}
