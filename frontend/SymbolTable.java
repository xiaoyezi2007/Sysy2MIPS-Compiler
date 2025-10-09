package frontend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class SymbolTable {
    private int id;
    private int SymbolCnt = 0;
    private SymbolTable father;
    private HashMap<String,Symbol> directory = new HashMap<>();
    private ArrayList<SymbolTable> children = new ArrayList<>();

    public SymbolTable(int id) {
        this.id = id;
    }

    public void addSymbol(String token, String type, String btype, String con) {
        SymbolCnt++;
        Symbol s = new Symbol(SymbolCnt, id, token, type, btype, con);
        directory.put(token, s);
    }

    public void addSymbol(String token, String type, String returnType) {
        SymbolCnt++;
        Symbol s = new Symbol(SymbolCnt, id, token, type, returnType);
        directory.put(token, s);
    }

    public SymbolTable getFather() {
        return father;
    }

    public void addChild(SymbolTable child) {
        children.add(child);
        child.father = this;
    }

    public void print() {
        ArrayList<Symbol> symbols = new ArrayList<>();
        for (Symbol s : directory.values()) {
            symbols.add(s);
        }
        Collections.sort(symbols);
        for (Symbol s : symbols) {
            s.print();
        }
        for (SymbolTable child : children) {
            child.print();
        }
    }
}
