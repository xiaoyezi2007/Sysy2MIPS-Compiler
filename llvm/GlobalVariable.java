package llvm;

import llvm.constant.Constant;
import llvm.constant.ConstantInt;

import java.util.ArrayList;

public class GlobalVariable extends GlobalValue {
    private Constant value = null;
    private ArrayList<Value> values = null;

    public GlobalVariable(String name, IRType type) {
        super(ValueType.GLOBAL_VARIABLE, type, "@"+name);
    }

    public boolean isInit() {
        return value != null;
    }

    public void setValue(ArrayList<Value> values) {
        this.values = values;
    }

    public Constant getValue() {
        return value;
    }

    public Value getKthEle(Constant index) {
        return values.get(Integer.valueOf(index.getName()));
    }

    @Override
    public void setValue(Constant value) {
        this.value = value;
    }

    @Override
    public void print() {
        if (values == null) {
            System.out.println(name+" = dso_local global i32 "+value.getName());
        }
        else {
            System.out.print(name+" = dso_local global "+getType().ptTo().toString()+" [");
            for(int i=0;i<values.size();i++) {
                System.out.print(values.get(i).getTypeName()+" "+values.get(i).getName());
                if(i!=values.size()-1) {
                    System.out.print(", ");
                }
            }
            System.out.println("]");
        }
    }
}
