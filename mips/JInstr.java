package mips;

import javax.swing.JLabel;

public class JInstr extends MipsInstr {
    private String label = "";
    private Register reg;

    public JInstr(String op, String label) {
        this(op, label, true);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public JInstr(String op, String label, boolean emit) {
        this.label = label;
        this.op = op;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public JInstr(String op, Register reg) {
        this(op, reg, true);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public JInstr(String op, Register reg, boolean emit) {
        this.op = op;
        this.reg = reg;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public String getLabel() {
        return label;
    }

    public Register getReg() {
        return reg;
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
