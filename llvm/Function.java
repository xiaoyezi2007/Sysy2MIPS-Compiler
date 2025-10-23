package llvm;

import llvm.constant.Constant;

import java.util.ArrayList;

public class Function extends GlobalValue {
    ArrayList<BasicBlock> basicBlocks = new ArrayList<>();
    private int num;
    private ArrayList<Value> params;

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
