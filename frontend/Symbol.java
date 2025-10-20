package frontend;

import llvm.Value;
import util.Error;

import java.util.ArrayList;

public class Symbol implements Comparable {
    private int id;
    private int tableId;
    private String token;
    private int type;   //0->var , 1->array, 2->func
    private int btype;  //0->int , 1->char
    private int con;    //0->const, 1->static, 2->var
    private int len;
    private int reg;
    private int val;
    private int returnType; //0->void, 1->int, 2->char
    private int paramNum;
    private ArrayList<Symbol> params = new ArrayList<>();
    private Value value;

    public Symbol(int id, int tableId, String token, String type, String btype, String con, Value value) {
        this.id = id;
        this.tableId = tableId;
        this.token = token;
        this.value = value;
        switch (type) {
            case "var":
                this.type = 0;
                break;
            case "array":
                this.type = 1;
                break;
            case "func":
                this.type = 2;
                break;
        }
        switch (btype) {
            case "int":
                this.btype = 0;
                break;
            case "char":
                this.btype = 1;
                break;
        }
        switch (con) {
            case "const":
                this.con = 0;
                break;
            case "static":
                this.con = 1;
                break;
            case "var":
                this.con = 2;
                break;
        }
    }

    public Symbol(int id, int tableId, String token, String type, String returnType, Value value) {
        this.id = id;
        this.tableId = tableId;
        this.token = token;
        this.value = value;
        switch (type) {
            case "var":
                this.type = 0;
                break;
            case "array":
                this.type = 1;
                break;
            case "func":
                this.type = 2;
                break;
        }
        switch (returnType) {
            case "void":
                this.returnType = 0;
                break;
            case "int":
                this.returnType = 1;
                break;
            case "char":
                this.returnType = 2;
                break;
        }
    }

    public void setValue(Value value) {
        this.value = value;
    }

    public boolean isConst() {
        return con == 0;
    }

    public void addParam(Symbol s) {
        params.add(s);
    }

    public boolean noParam() {
        return params.isEmpty();
    }

    public int getParamNum() {
        return params.size();
    }

    public boolean isArray() {
        return type == 1;
    }

    public boolean sameKindOnlyType(int type) {
        if (this.type == 0) return type == 0;
        if (this.type == 1) return type >0;
        return false;
    }

    public boolean returnInt() {
        return returnType == 1;
    }

    public boolean checkParams(ArrayList<Integer> types) {
        for (int i = 0; i < params.size(); i++) {
            if (!params.get(i).sameKindOnlyType(types.get(i))) {
                return false;
            }
        }
        return true;
    }

    public Value getValue() {
        return this.value;
    }

    public void print() {
        if (type == 0 && btype == 0 && con == 0) System.out.println(tableId + " " + token + " ConstInt");
        else if (type == 0 && btype == 0 && con == 1) System.out.println(tableId + " " + token + " StaticInt");
        else if (type == 0 && btype == 0 && con == 2) System.out.println(tableId + " " + token + " Int");
        else if (type == 1 && btype == 0 && con == 0) System.out.println(tableId + " " + token + " ConstIntArray");
        else if (type == 1 && btype == 0 && con == 1) System.out.println(tableId + " " + token + " StaticIntArray");
        else if (type == 1 && btype == 0 && con == 2) System.out.println(tableId + " " + token + " IntArray");
        else if (type == 2 && returnType == 0) System.out.println(tableId + " " + token + " VoidFunc");
        else if (type == 2 && returnType == 1) System.out.println(tableId + " " + token + " IntFunc");
    }

    @Override
    public int compareTo(Object o) {
        Symbol s = (Symbol) o;
        if (s.id < id) {
            return 1;
        }
        else {
            return -1;
        }
    }
}
