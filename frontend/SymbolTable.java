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
    public Symbol last = null;

    public SymbolTable(int id) {
        this.id = id;
    }

    public boolean addSymbol(String token, String type, String btype, String con) {
        if (directory.containsKey(token)) {
            return false;
        }
        SymbolCnt++;
        Symbol s = new Symbol(SymbolCnt, id, token, type, btype, con);
        directory.put(token, s);
        return true;
    }

    public boolean addSymbol(String token, String type, String returnType) {
        Symbol s = new Symbol(SymbolCnt+1, id, token, type, returnType);
        last = s;
        if (directory.containsKey(token)) {
            return false;
        }
        SymbolCnt++;
        directory.put(token, s);
        return true;
    }

    public SymbolTable getFather() {
        return father;
    }

    public void addChild(SymbolTable child) {
        children.add(child);
        child.father = this;
    }

    public boolean checkSymbol(String token) {
        if (directory.containsKey(token)) return true;
        if (father != null) return father.checkSymbol(token);
        return false;
    }

    public Symbol getSymbol(String token) {
        if (directory.containsKey(token)) return directory.get(token);
        if (father != null) return father.getSymbol(token);
        return null;
    }

    public int getId() {
        return id;
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
