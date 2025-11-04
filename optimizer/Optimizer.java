package optimizer;

import mips.IInstr;
import mips.JInstr;
import mips.Label;
import mips.MipsInstr;
import mips.MipsModule;
import mips.MipsString;
import mips.MipsWord;

import java.util.ArrayList;

public class Optimizer {
    private MipsModule mipsModule;
    private ArrayList<MipsInstr> Instrs;
    private ArrayList<MipsWord> Words;
    private ArrayList<MipsString> Strings;

    public Optimizer(MipsModule mipsModule) {
        this.mipsModule = mipsModule;
    }

    public MipsModule optimize() {
        Instrs = mipsModule.getInstrs();
        Words = mipsModule.getWords();
        Strings = mipsModule.getStrings();
        MipsModule optimizedModule = new MipsModule();
        for (MipsWord word : Words) {
            optimizedModule.addWord(word);
        }
        for (MipsString string : Strings) {
            optimizedModule.addString(string);
        }

        //multDivOptimize();
        pesudoInstrOptimize();
        peepholeOptimize();

        for (MipsInstr instr : Instrs) {
            optimizedModule.addInstr(instr);
        }

        return optimizedModule;
    }

    public void multDivOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("mul")) {}
        }
    }

    public void pesudoInstrOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("subi")) {
                IInstr iInstr = (IInstr) instr;
                int x = (iInstr).getImmediate();
                optInstrs.add(new IInstr("addi", iInstr.getRs(), iInstr.getRt(), -x));
            }
            else {
                optInstrs.add(instr);
            }
        }
        Instrs = optInstrs;
    }

    public void peepholeOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0;i < Instrs.size();i++) {
            boolean rm = false;
            MipsInstr now = Instrs.get(i);
            if (i < Instrs.size() - 1) {
                MipsInstr nxt = Instrs.get(i + 1);
                if (now instanceof JInstr && nxt instanceof Label) {
                    JInstr j = (JInstr) now;
                    Label l = (Label) nxt;
                    if (j.getOp().equals("j") && j.getLabel().equals(l.getName())) {
                        rm = true;
                    }
                }
            }
            if (!rm) {
                optInstrs.add(now);
            }
        }
        Instrs = optInstrs;
    }
}
