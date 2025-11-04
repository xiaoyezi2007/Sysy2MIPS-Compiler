package mips;

public class Label extends MipsInstr {
    private String label;
    private int stackSpace = -1;

    public Label(String label) {
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    public String getName() {
        return label;
    }

    public void setStackSpace(int space) {
        stackSpace = space;
    }

    public void print() {
        System.out.println(label+":");
    }
}
