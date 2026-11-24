package org.example.filemanager;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads bundletool's public commands.proto / targeting.proto, without loading desktop bundletool. */
final class ApksTargeting {
    record Profile(Set<String> groups, Integer tier, String countrySet, String countryCode) {
        static final Profile AUTOMATIC = new Profile(null, null, null, null);
    }
    enum Dimension { GROUP, TIER, COUNTRY_SET, COUNTRY_CODE }
    static final class ProfileRequired extends IOException {
        final Dimension dimension;
        final List<String> options;
        ProfileRequired(Dimension dimension, Collection<String> options) {
            super("请提供安装包所需的设备或地区条件");
            this.dimension = dimension;
            this.options = List.copyOf(options);
        }
    }
    record Device(List<String> abis, Set<String> languages, int density, int sdk, Set<Integer> textures) {
        Device(List<String> abis, Set<String> languages, int density, int sdk) {
            this(abis, languages, density, sdk, Set.of());
        }
        static Device current(Context context) {
            Set<String> languages = new LinkedHashSet<>(); var locales = context.getResources().getConfiguration().getLocales();
            for (int i = 0; i < locales.size(); i++) languages.add(language(locales.get(i).getLanguage()));
            return new Device(List.of(Build.SUPPORTED_ABIS), languages, context.getResources().getDisplayMetrics().densityDpi,
                    Build.VERSION.SDK_INT, TextureSupport.current(context));
        }
        String summary() { return "ABI：" + String.join(", ", abis) + "\n语言：" + String.join(", ", languages) + "\n密度：" + density + " dpi · Android API " + sdk; }
    }
    record Selection(Set<String> paths, String description) { }
    // protobuf wire parser: bounded input, bounded fields, no recursive decoding of unknown fields.
    static final class Message {
        final Map<Integer, List<Object>> fields = new HashMap<>();
        Message(byte[] data) throws IOException {
            int[] at = {0}; int count = 0;
            while (at[0] < data.length) {
                if (++count > 100000) throw new IOException("APKS 元数据字段过多");
                long tag = varint(data, at); int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                if (field < 1 || tag >>> 3 > 536870911) throw new IOException("APKS 元数据字段无效");
                Object value;
                if (wire == 0) value = varint(data, at);
                else if (wire == 2) {
                    long length = varint(data, at); if (length < 0 || length > data.length - at[0]) throw new IOException("APKS 元数据长度无效");
                    value = Arrays.copyOfRange(data, at[0], at[0] + (int) length); at[0] += (int) length;
                } else if (wire == 1 || wire == 5) {
                    int size = wire == 1 ? 8 : 4; if (size > data.length - at[0]) throw new IOException("APKS 元数据不完整");
                    value = Arrays.copyOfRange(data, at[0], at[0] + size); at[0] += size;
                } else throw new IOException("不支持的 APKS 元数据编码");
                fields.computeIfAbsent(field, k -> new ArrayList<>()).add(value);
            }
        }
        private static long varint(byte[] data, int[] at) throws IOException {
            long value = 0;
            for (int i = 0; i < 10; i++) {
                if (at[0] >= data.length) throw new IOException("APKS 元数据不完整");
                int b = data[at[0]++] & 255; if (i == 9 && b > 1) throw new IOException("APKS 整数溢出");
                value |= (long) (b & 127) << (7 * i); if ((b & 128) == 0) return value;
            } throw new IOException("APKS 整数编码无效");
        }
        List<Message> messages(int field) throws IOException {
            List<Message> result = new ArrayList<>(); for (Object value : fields.getOrDefault(field, List.of())) {
                if (!(value instanceof byte[] bytes)) throw new IOException("APKS 消息类型无效"); result.add(new Message(bytes));
            } return result;
        }
        Message message(int field) throws IOException { List<Message> list = messages(field); return list.isEmpty() ? new Message(new byte[0]) : list.get(list.size() - 1); }
        int integer(int field) throws IOException {
            long value = longInteger(field);
            if (value > Integer.MAX_VALUE) throw new IOException("APKS 数值无效");
            return (int) value;
        }
        long longInteger(int field) throws IOException {
            List<Object> list = fields.get(field); if (list == null) return 0; Object value = list.get(list.size() - 1);
            if (!(value instanceof Long number) || number < 0) throw new IOException("APKS 数值无效"); return number;
        }
        List<String> strings(int field) throws IOException {
            List<String> values = new ArrayList<>(); for (Object value : fields.getOrDefault(field, List.of())) {
                if (!(value instanceof byte[] bytes)) throw new IOException("APKS 字符串类型无效"); values.add(new String(bytes, StandardCharsets.UTF_8));
            } return values;
        }
        String text(int field) throws IOException { List<String> values = strings(field); return values.isEmpty() ? "" : values.get(values.size() - 1); }
        boolean has(int field) { return fields.containsKey(field); }
    }
    static Selection select(byte[] toc, Device device, Context context) throws IOException {
        return select(toc, device, context, Profile.AUTOMATIC);
    }
    static Selection select(byte[] toc, Device device, Context context, Profile requested) throws IOException {
        if (toc.length > 8 * 1024 * 1024) throw new IOException("APKS 目录元数据超过 8 MB");
        Message result = new Message(toc); Message selected = null; int priority = -1;
        for (Message variant : result.messages(1)) {
            if (!matches(variant.message(1), device, true, requested)) continue;
            boolean installable = false;
            for (Message set : variant.messages(2)) for (Message apk : set.messages(2)) if (apk.has(3) || apk.has(4)) installable = true;
            int number = variant.integer(3);
            if (installable && number > priority) { selected = variant; priority = number; }
        }
        if (selected == null) throw new IOException("APKS 没有适合此设备的安装变体");
        Set<String> groups = requested.groups;
        if (groups == null && context != null && result.has(9)) groups = resolveGroups(result.message(9), context);
        Profile profile = new Profile(groups, requested.tier, requested.countrySet, requested.countryCode);
        Map<String, Message> modules = new LinkedHashMap<>(); Set<String> include = new LinkedHashSet<>();
        for (Message set : selected.messages(2)) {
            Message metadata = set.message(1); String name = metadata.text(1);
            if (modules.put(name, set) != null) throw new IOException("APKS 模块重名：" + name);
            if (metadata.integer(3) != 0) continue; // instant-only module
            if (name.equals("base") || metadata.integer(2) == 0 && metadata.integer(6) != 2 && moduleMatches(metadata.message(5), device, context, profile)) include.add(name);
        }
        Deque<String> dependencies = new ArrayDeque<>(include);
        while (!dependencies.isEmpty()) {
            Message set = modules.get(dependencies.removeFirst()); if (set == null) throw new IOException("APKS 缺少所需模块");
            for (String dependency : set.message(1).strings(4)) {
                if (!modules.containsKey(dependency)) throw new IOException("APKS 缺少依赖模块：" + dependency);
                if (include.add(dependency)) dependencies.add(dependency);
            }
        }
        Set<String> paths = new LinkedHashSet<>();
        for (String module : include) {
            Message set = modules.get(module);
            for (Message apk : set.messages(2)) {
                if (!apk.has(3) && !apk.has(4)) continue;
                if (!matches(apk.message(1), device, false, profile)) continue;
                String path = apk.text(2); ArchiveOps.validateEntry(path);
                if (!path.endsWith(".apk") || !paths.add(path)) throw new IOException("APKS 目录中 APK 路径无效或重复");
            }
        }
        if (paths.isEmpty()) throw new IOException("没有匹配当前设备的 APK");
        return new Selection(Collections.unmodifiableSet(paths), "标准 APKS · 设备变体 " + priority);
    }
    private static boolean moduleMatches(Message target, Device device, Context context, Profile profile) throws IOException {
        if (!sdk(target.message(1), device.sdk)) return false;
        for (int field : target.fields.keySet()) if (field != 1 && field != 2 && field != 3 && field != 5)
            throw new IOException("此 APKS 含暂不支持的模块条件：" + field);
        for (Message feature : target.messages(2)) {
            Message value = feature.message(1); if (context == null || !context.getPackageManager().hasSystemFeature(value.text(1), value.integer(2))) return false;
        }
        if (!groupsMatch(target.message(5), profile.groups)) return false;
        Message countries = target.message(3);
        List<String> values = countries.strings(1);
        if (values.isEmpty()) return true;
        if (profile.countryCode == null) throw new ProfileRequired(Dimension.COUNTRY_CODE, List.of());
        boolean included = values.stream().anyMatch(profile.countryCode::equalsIgnoreCase);
        return countries.integer(2) == 0 ? included : !included;
    }
    private static boolean matches(Message target, Device device, boolean variant, Profile profile) throws IOException {
        Set<Integer> known = variant ? Set.of(1, 2, 3, 4, 5, 6) : Set.of(1, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        for (int field : target.fields.keySet()) if (!known.contains(field)) throw new IOException("此 APKS 含暂不支持的设备条件：" + field);
        if (variant && target.has(6) && target.message(6).integer(1) != 0) return false;
        if (!variant && target.has(8) && target.message(8).message(1).integer(1) != 0) return false;
        if (!sdk(target.message(variant ? 1 : 5), device.sdk)) return false;
        if (!abi(target.message(variant ? 2 : 1), device.abis)) return false;
        if (!density(target.message(variant ? 3 : 4), device.density)) return false;
        if (!multiAbi(target.message(variant ? 4 : 7), device.abis)) return false;
        if (!texture(target.message(variant ? 5 : 6), device.textures)) return false;
        if (!variant) {
            Message language = target.message(3); List<String> values = language.strings(1), alternatives = language.strings(2);
            if (!values.isEmpty() && values.stream().map(ApksTargeting::language).noneMatch(device.languages::contains)) return false;
            if (values.isEmpty() && !alternatives.isEmpty() && new HashSet<>(alternatives).containsAll(device.languages)) return false;
            if (!groupsMatch(target.message(11), profile.groups)) return false;
            if (!tiersMatch(target.message(9), profile.tier)) return false;
            if (!countriesMatch(target.message(10), profile.countrySet)) return false;
        }
        return true;
    }
    private static boolean groupsMatch(Message target, Set<String> groups) throws IOException {
        List<String> values = target.strings(1), alternatives = target.strings(2);
        if (values.isEmpty() && alternatives.isEmpty()) return true;
        if (groups == null) throw new ProfileRequired(Dimension.GROUP, options(values, alternatives));
        return !Collections.disjoint(values, groups);
    }
    private static boolean countriesMatch(Message target, String country) throws IOException {
        List<String> values = target.strings(1), alternatives = target.strings(2);
        if (values.isEmpty() && alternatives.isEmpty()) return true;
        if (!Collections.disjoint(values, alternatives)) throw new IOException("APKS 地区条件相互冲突");
        if (country == null) throw new ProfileRequired(Dimension.COUNTRY_SET, options(values, alternatives));
        if (country.isEmpty()) return values.isEmpty();
        return values.contains(country);
    }
    private static boolean tiersMatch(Message target, Integer tier) throws IOException {
        List<String> values = new ArrayList<>(), alternatives = new ArrayList<>();
        for (Message value : target.messages(3)) values.add(Integer.toString(value.integer(1)));
        for (Message value : target.messages(4)) alternatives.add(Integer.toString(value.integer(1)));
        if (values.isEmpty() && alternatives.isEmpty()) return true;
        if (!Collections.disjoint(values, alternatives)) throw new IOException("APKS 设备层级条件相互冲突");
        if (tier == null) throw new ProfileRequired(Dimension.TIER, options(values, alternatives));
        return values.contains(tier.toString());
    }
    private static Collection<String> options(List<String> values, List<String> alternatives) {
        Set<String> options = new LinkedHashSet<>(values);
        options.addAll(alternatives);
        return options;
    }
    private static Set<String> resolveGroups(Message config, Context context) throws IOException {
        var memory = new android.app.ActivityManager.MemoryInfo();
        var manager = context.getSystemService(android.app.ActivityManager.class);
        if (manager != null) manager.getMemoryInfo(memory);
        Set<String> groups = new LinkedHashSet<>();
        for (Message group : config.messages(1)) {
            for (Message selector : group.messages(2)) {
                if (selectorMatches(selector, context, memory.totalMem)) { groups.add(group.text(1)); break; }
            }
        }
        return groups;
    }
    private static boolean selectorMatches(Message selector, Context context, long memory) throws IOException {
        for (int field : selector.fields.keySet()) if (field < 1 || field > 6)
            throw new IOException("APKS 设备组包含暂不支持的自动识别条件");
        Message ram = selector.message(1);
        long minimum = ram.longInteger(1), maximum = ram.longInteger(2);
        if (memory < minimum || maximum > 0 && memory >= maximum) return false;
        List<Message> included = selector.messages(2), excluded = selector.messages(3);
        if ((!included.isEmpty() && !containsDevice(included, Build.BRAND, Build.DEVICE))
                || containsDevice(excluded, Build.BRAND, Build.DEVICE)) return false;
        var manager = context.getPackageManager();
        for (Message item : selector.messages(4)) if (!manager.hasSystemFeature(item.text(1))) return false;
        for (Message item : selector.messages(5)) if (manager.hasSystemFeature(item.text(1))) return false;
        List<Message> chips = selector.messages(6);
        return chips.isEmpty() || Build.VERSION.SDK_INT >= 31 && containsDevice(chips, Build.SOC_MANUFACTURER, Build.SOC_MODEL);
    }
    private static boolean containsDevice(List<Message> list, String brand, String model) throws IOException {
        for (Message device : list) if (brand.equals(device.text(1)) && model.equals(device.text(2))) return true;
        return false;
    }
    static boolean texture(Message target, Set<Integer> supported) throws IOException {
        Set<Integer> values = new HashSet<>(), alternatives = new HashSet<>();
        for (Message value : target.messages(1)) values.add(value.integer(1));
        for (Message value : target.messages(2)) alternatives.add(value.integer(1));
        if (!Collections.disjoint(values, alternatives)) throw new IOException("APKS 纹理条件相互冲突");
        for (int preference : new int[]{9, 8, 7, 6, 5, 4, 3, 10, 1, 2}) {
            if (!supported.contains(preference)) continue;
            if (values.contains(preference)) return true;
            if (alternatives.contains(preference)) return false;
        }
        return values.isEmpty() || values.contains(0);
    }
    private static boolean sdk(Message target, int sdk) throws IOException {
        int value = 0, best = 0;
        for (Message item : target.messages(1)) value = Math.max(value, item.message(1).integer(1));
        if (value > sdk) return false;
        for (Message item : target.messages(2)) { int min = item.message(1).integer(1); if (min <= sdk) best = Math.max(best, min); }
        return value >= best;
    }
    static String abiName(int alias) { return switch (alias) { case 1 -> "armeabi"; case 2 -> "armeabi-v7a"; case 3 -> "arm64-v8a"; case 4 -> "x86"; case 5 -> "x86_64"; case 6 -> "mips"; case 7 -> "mips64"; case 8 -> "riscv64"; default -> "unknown"; }; }
    private static boolean abi(Message target, List<String> abis) throws IOException {
        Set<String> values = new HashSet<>(), alternatives = new HashSet<>();
        for (Message item : target.messages(1)) values.add(abiName(item.integer(1))); for (Message item : target.messages(2)) alternatives.add(abiName(item.integer(1)));
        if (values.isEmpty() && alternatives.isEmpty()) return true;
        for (String abi : abis) { if (values.contains(abi)) return true; if (alternatives.contains(abi)) return false; }
        return values.isEmpty();
    }
    private static boolean multiAbi(Message target, List<String> abis) throws IOException {
        List<Message> values = target.messages(1), alternatives = target.messages(2); if (values.isEmpty() && alternatives.isEmpty()) return true;
        List<Integer> bestValue = null, bestAlternative = null;
        for (Message item : values) { List<Integer> rank = multiRank(item, abis); if (rank != null && (bestValue == null || compareAbis(rank, bestValue) > 0)) bestValue = rank; }
        for (Message item : alternatives) { List<Integer> rank = multiRank(item, abis); if (rank != null && (bestAlternative == null || compareAbis(rank, bestAlternative) > 0)) bestAlternative = rank; }
        return bestValue != null && (bestAlternative == null || compareAbis(bestValue, bestAlternative) >= 0);
    }
    private static List<Integer> multiRank(Message item, List<String> abis) throws IOException {
        Set<Integer> aliases = new TreeSet<>(Comparator.reverseOrder());
        for (Message abi : item.messages(1)) { int alias = abi.integer(1); if (!abis.contains(abiName(alias))) return null; aliases.add(alias); }
        return new ArrayList<>(aliases);
    }
    private static int compareAbis(List<Integer> first, List<Integer> second) {
        for (int i = 0; i < Math.min(first.size(), second.size()); i++) { int compared = Integer.compare(first.get(i), second.get(i)); if (compared != 0) return compared; }
        return Integer.compare(first.size(), second.size());
    }
    static int densityValue(Message item) throws IOException {
        if (item.has(2)) return item.integer(2);
        return switch (item.integer(1)) { case 1 -> 65535; case 2 -> 120; case 3 -> 160; case 4 -> 213; case 5 -> 240; case 6 -> 320; case 7 -> 480; case 8 -> 640; default -> 160; };
    }
    private static boolean density(Message target, int desired) throws IOException {
        Set<Integer> values = new HashSet<>(), all = new HashSet<>();
        for (Message item : target.messages(1)) values.add(densityValue(item)); all.addAll(values);
        for (Message item : target.messages(2)) all.add(densityValue(item));
        return all.isEmpty() || values.contains(bestDensity(all, desired));
    }
    // Android resource scaling prefers downsampling; simply taking the nearest dpi is incorrect.
    static int bestDensity(Collection<Integer> values, int desired) {
        int best = 0; for (int candidate : new TreeSet<>(values)) {
            if (best == 0 || candidate == 65534) { best = candidate; continue; }
            if (best == 65534) continue;
            int low = Math.min(best, candidate), high = Math.max(best, candidate);
            best = desired >= high ? high : desired <= low ? low : ((2L * low - desired) * high > (long) desired * desired ? low : high);
        } return best;
    }
    static String language(String input) { return switch (input) { case "iw" -> "he"; case "in" -> "id"; case "ji" -> "yi"; default -> input; }; }
    private ApksTargeting() { }
}
