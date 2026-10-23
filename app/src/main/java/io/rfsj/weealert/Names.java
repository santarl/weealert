package io.rfsj.weealert;

import java.util.HashSet;

/** Heroku-style adjective-noun names (64 x 64), derived from the pattern itself so a sound keeps its name. */
final class Names {
    private static final String[] ADJ = {
        "brisk", "calm", "clever", "cosmic", "crisp", "daring", "dusty", "eager", "fuzzy",
        "gentle", "glossy", "golden", "happy", "hazy", "honest", "icy", "jolly", "keen", "lively",
        "lucky", "mellow", "misty", "nimble", "noble", "odd", "peppy", "plucky", "polite", "proud",
        "quiet", "rapid", "rusty", "shiny", "silent", "sleepy", "snowy", "solar", "spicy",
        "steady", "sunny", "swift", "tidy", "tiny", "vivid", "warm", "wild", "witty", "zesty",
        "amber", "bold", "breezy", "bright", "cozy", "curly", "dapper", "epic", "fancy", "frosty",
        "giddy", "grand", "humble", "jazzy", "lunar", "merry"
    };
    private static final String[] NOUN = {
        "otter", "falcon", "maple", "comet", "harbor", "lantern", "meadow", "pebble", "river",
        "sparrow", "thistle", "walrus", "acorn", "badger", "canyon", "daisy", "ember", "fjord",
        "glacier", "heron", "island", "jaguar", "kettle", "lagoon", "marmot", "nectar", "orchid",
        "panda", "quartz", "raven", "saffron", "tundra", "willow", "yak", "zephyr", "anchor",
        "beacon", "cactus", "dune", "eagle", "fern", "gecko", "hazel", "iris", "juniper", "koala",
        "lotus", "mango", "newt", "oasis", "pine", "quill", "reef", "sprout", "tulip", "urchin",
        "violet", "wombat", "yarrow", "anvil", "bison", "cedar", "dingo", "ibis"
    };

    private static int fnv(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 16777619; }
        return h;
    }

    static String[] forPatterns(Codebook.Pattern[] ps) {
        String[] out = new String[ps.length];
        HashSet<String> used = new HashSet<>();
        for (int i = 0; i < ps.length; i++) {
            String key = ps[i].key(), name;
            int salt = 0;
            do {
                int h = fnv(salt == 0 ? key : key + "#" + salt);
                int r = (h ^ (h >>> 15)) & 0xFFF;
                name = ADJ[r & 63] + "-" + NOUN[r >>> 6];
                salt++;
            } while (!used.add(name));
            out[i] = name;
        }
        return out;
    }
}
