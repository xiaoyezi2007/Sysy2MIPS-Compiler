package mips;

public class MipsString {
    private String value;
    private String name;

    public MipsString(String name, String value) {
        this.value = value;
        this.name = name;
    }

    public void print() {
        System.out.println(name+": .asciiz \""+value+'"');
    }
}
