package iad1tya.echo.music.dpi.core

enum class DpiStrategy(val title: String, val params: String) {
    PRO_1("Pro 1: Mixed Split", "-d1-d3+s -sb+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -al -As -d1-d3+s -se+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -al"),
    PRO_2("Pro 2: Aggressive Fragment", "-o2 -S2 -s3+S -r3 -S4 -r4 -S5+S -r5+s -s6 -S7+s -r8 -s9+s -Qr -Mh,d,r -a1 -At,r -s2+s -r2 -d2 -s3 -r3 -r4 -s4 -d5+s -r5 -d6 -s7+s -d7 -al"),
    PRO_3("Pro 3: Balanced Split", "-o1-d1-al -At,r,s -s1-d1-s5+s -s10+s -s15+s -s20+s -r1+s -S -al-As -s1-d1-s5+s -s10+s -s15+s -s20+s -S -al"),
    PRO_4("Pro 4: Fast Stream", "-d1+s -s50+s -al-As -f20 -r2+s -al -At -d2 -sl+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -al"),
    PRO_5("Pro 5: Minimal OOB", "-o1-al -At,r,s -f-1-al-At,r,s -d1:11+sm -S -al-At,r,s -n google.com -Qr -f1 -d1:11+sm -s1:11+sm -S -al"),
    PRO_6("Pro 6: Stealth", "-d1-s1-q1-Y -al-Ar -s5 -o1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -al"),
    PRO_7("Pro 7: Heavy Payload", "-d1-s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -al"),
    PRO_8("Pro 8: Legacy Mix", "-s1-q1-al-Y -Ar -al-s5 -o2 -At -f-1-r1+s -al -As -s1 -o1+s -s-1-al")
}
