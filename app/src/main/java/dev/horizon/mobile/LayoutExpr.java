package dev.horizon.mobile;

import java.util.Map;

/**
 * Evaluates the position expressions of a Mojo layout: numbers, + - * / ^, parentheses,
 * dp(x), px(x) and ${variables}. Anything it cannot read evaluates to 0.
 */
final class LayoutExpr {
    private final String s;
    private final Map<String, Float> vars;
    private final float density;
    private int i;

    private LayoutExpr(String s, Map<String, Float> vars, float density) {
        this.s = s;
        this.vars = vars;
        this.density = density;
    }

    static float eval(String expr, Map<String, Float> vars, float density) {
        try {
            LayoutExpr e = new LayoutExpr(expr, vars, density);
            double v = e.expr();
            return Double.isNaN(v) || Double.isInfinite(v) ? 0f : (float) v;
        } catch (RuntimeException ex) {
            return 0f;
        }
    }

    private void skip() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private boolean eat(char c) {
        skip();
        if (i < s.length() && s.charAt(i) == c) { i++; return true; }
        return false;
    }

    private double expr() {
        double v = term();
        for (;;) {
            if (eat('+')) v += term();
            else if (eat('-')) v -= term();
            else return v;
        }
    }

    private double term() {
        double v = unary();
        for (;;) {
            if (eat('*')) v *= unary();
            else if (eat('/')) { double d = unary(); v = d == 0 ? 0 : v / d; }
            else return v;
        }
    }

    private double unary() {
        if (eat('-')) return -unary();
        if (eat('+')) return unary();
        double b = atom();
        if (eat('^')) return Math.pow(b, unary());
        return b;
    }

    private double atom() {
        skip();
        if (i >= s.length()) throw new IllegalArgumentException("end");
        if (eat('(')) { double v = expr(); eat(')'); return v; }
        if (s.startsWith("${", i)) {
            int e = s.indexOf('}', i);
            if (e < 0) throw new IllegalArgumentException("}");
            String n = s.substring(i + 2, e);
            i = e + 1;
            Float v = vars.get(n);
            return v == null ? 0 : v;
        }
        char c = s.charAt(i);
        if (Character.isDigit(c) || c == '.') {
            int st = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i < s.length() && Character.isDigit(s.charAt(i))) {
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                } else {
                    i = save;
                }
            }
            return Double.parseDouble(s.substring(st, i));
        }
        if (Character.isLetter(c)) {
            int st = i;
            while (i < s.length() && Character.isLetter(s.charAt(i))) i++;
            String f = s.substring(st, i);
            if (!eat('(')) throw new IllegalArgumentException("(");
            double a = expr();
            eat(')');
            if (f.equals("dp")) return a / density;
            if (f.equals("px")) return a * density;
            throw new IllegalArgumentException(f);
        }
        throw new IllegalArgumentException("char " + c);
    }
}
