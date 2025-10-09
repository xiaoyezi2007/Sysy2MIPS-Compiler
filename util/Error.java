package util;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collections;

public class Error {
    private ArrayList<e> errors = new ArrayList<>();
    private Tool tool = new Tool();

    private class e implements Comparable {
        protected int line;
        protected String kind;

        public e(String kind, int line) {
            this.line = line;
            this.kind = kind;
        }

        @Override
        public int compareTo(Object o) {
            e s = (e) o;
            if (s.line < line) {
                return 1;
            }
            else {
                return -1;
            }
        }

        public void printE() {
            System.out.println(line + " " + kind);
        }
    }

    public void addError(String kind, int line) {
        errors.add(new e(kind, line));
    }

    public Boolean isError() {
        return !errors.isEmpty();
    }

    public void check1() {
        for(int i=0;i<errors.size()-1;i++) {
            if(errors.get(i).line == errors.get(i+1).line && errors.get(i+1).kind.equals("k")) {
                while(true);
            }
        }
    }

    public void printError() throws FileNotFoundException {
        tool.setOutput("error.txt");
        Collections.sort(errors);
        //check1();
        for (int i=0;i<errors.size();i++) {
            errors.get(i).printE();
        }
    }
}
