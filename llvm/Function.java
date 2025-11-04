package llvm;

import llvm.constant.Constant;
import mips.IInstr;
import mips.Label;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;

import java.util.ArrayList;

public class Function extends GlobalValue {
    ArrayList<BasicBlock> basicBlocks = new ArrayList<>();
    private int num;
    private ArrayList<Value> params = new ArrayList<>();
    private int stackSpace = 0;

    public Function(String type, String name) {
        super(ValueType.FUNCTION, new IRType(type), name);
    }

    public void setParameters(ArrayList<Value> parameters) {
        this.num = parameters.size();
        this.params = parameters;
    }

    public boolean isReturn() {
        return getType().equals("int");
    }

    public void addBasicBlock(BasicBlock basicBlock) {
        basicBlocks.add(basicBlock);
    }

    public ArrayList<BasicBlock> getBasicBlocks() {
        return basicBlocks;
    }

    public int getStackSpace() {
        return stackSpace;
    }

    @Override
    public void toMips() {
        for (BasicBlock basicBlock : basicBlocks) {
            stackSpace += basicBlock.getSpace();
        }
        for (Value param : params) {
            stackSpace += 4;
        }
        stackSpace += 4; // for $ra
        if (getName().equals("main")) {
            MipsBuilder.isMain = true;
        }
        MipsBuilder.curFunc = this;
        new Label(getName());
        new IInstr("subi", this);
        new LswInstr("sw", Register.RA, Register.SP, 0);
        MipsBuilder.memory -= 4;
        for (Value param : params) {
            if (param.getType().equals("ptr")) {
                param.getType().isAddr = true;
            }
            param.memory = MipsBuilder.memory;
            MipsBuilder.memory -= 4;
        }
        for (int i = 0; i < basicBlocks.size(); i++) {
            if (i != 0) {
                new Label(getName()+"."+basicBlocks.get(i).getName());
            }
            basicBlocks.get(i).toMips();
        }
        stackSpace = -MipsBuilder.memory;
        MipsBuilder.memory = 0;
    }

    public void print() {
        System.out.print("define dso_local ");
        if (isReturn()) {
            System.out.print("i32");
        }
        else {
            System.out.print("void");
        }
        System.out.print(" @"+name+"(");
        for (int i=0;i<num;i++) {
            System.out.print(params.get(i).getTypeName()+" "+params.get(i).getName());
            if (i<num-1) {
                System.out.print(", ");
            }
        }
        System.out.println(") {");
        for (int i = 0; i < basicBlocks.size(); i++) {
            if (i!=0) {
                System.out.println();
                System.out.println(basicBlocks.get(i).getName()+":");
            }
            basicBlocks.get(i).print();
        }
        System.out.println("}");
    }
}
