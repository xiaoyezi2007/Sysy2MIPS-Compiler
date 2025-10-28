package mips;

import java.util.ArrayList;

public class MipsWord {
    private String name;
    private int x;
    private ArrayList<Integer> values = new ArrayList<>();

    public MipsWord(String name, ArrayList<Integer> values) {
        this.name = name;
        this.values = values;
    }

    public MipsWord(String name, int x) {
        this.name = name;
        this.x = x;
    }

    public boolean isArray() {
        return !values.isEmpty();
    }

    public void print() {
        if (isArray()) {
            System.out.print(name+": .word ");
            for (int i = 0; i < values.size(); i++) {
                System.out.print(values.get(i)+" ");
                if (i < values.size() - 1) {
                    System.out.print(", ");
                }
            }
            System.out.println();
        }
        else {
            System.out.println(name+": .word "+x);
        }
    }
}
