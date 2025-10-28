package mips;

import javax.swing.JLabel;

public class JInstr extends MipsInstr {
    private String label = "";
    private Register reg;

    public JInstr(String op, String label) {
        this.label = label;
        this.op = op;
        MipsBuilder.addInstr(this);
    }

    public JInstr(String op, Register reg) {
        this.op = op;
        this.reg = reg;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        if (!label.equals("")) {
            System.out.println(op+" "+label);
        }
        else {
            System.out.println(op+" "+reg);
        }
    }
}
