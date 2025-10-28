package mips;

import mips.fake.LiInstr;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class MipsModule {
    public ArrayList<MipsWord> Words = new ArrayList<>();
    public ArrayList<MipsString> Strings = new ArrayList<>();
    public ArrayList<MipsInstr> Instrs = new ArrayList<>();
    public Tool tool = new Tool();

    public MipsModule() {}

    public void addWord(MipsWord word) {
        Words.add(word);
    }

    public void addString(MipsString string) {
        Strings.add(string);
    }

    public void addInstr(MipsInstr instr) {
        Instrs.add(instr);
    }

    public void print() {
        try {
            tool.setOutput("mips.txt");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        System.out.println(".data");
        for (MipsWord word : Words) {
            System.out.print('\t');
            word.print();
        }
        for (MipsString string : Strings) {
            System.out.print('\t');
            string.print();
        }
        System.out.println(".text");
        for (MipsInstr instr : Instrs) {
            if (!(instr instanceof Label)) {
                System.out.print('\t');
            }
            instr.print();
        }
    }
}
