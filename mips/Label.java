package mips;

public class Label extends MipsInstr {
    String label;

    public Label(String label) {
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println(label+":");
    }
}
