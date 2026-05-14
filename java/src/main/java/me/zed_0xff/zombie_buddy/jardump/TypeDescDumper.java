/*
    static String formatModifiers(net.bytebuddy.description.ModifierReviewable mr) {
        int mod = mr.getModifiers();
        boolean isPackagePrivate = !Modifier.isPublic(mod) && !Modifier.isProtected(mod) && !Modifier.isPrivate(mod);
        String mods = Modifier.toString(mod);
        if (isPackagePrivate) mods = PKG_PRIVATE + " " + mods;
        if (Utils.isBlank(mods)) return "";
        return highlight( mods, _modifierColors ) + " ";
    }

    static String formatAnnotations(AnnotationSource as) {
        final boolean wasParams[] = {false}; // hack to modify from lambda
        ArrayList<String> anns = new ArrayList<>();
        as.getDeclaredAnnotations().forEach(a -> {
            StringBuilder sb = new StringBuilder();
            sb.append("@").append(a.getAnnotationType().getTypeName());
            int color = MAGENTA;
            if (a.getAnnotationType().getName().startsWith("net.bytebuddy.")) {
                color = CYAN;
            }
            MethodList<MethodDescription.InDefinedShape> params = a.getAnnotationType().getDeclaredMethods();
            if (!params.isEmpty()) {
                StringJoiner sj = new StringJoiner(", ");
                params.forEach(m -> {
                    AnnotationValue<?, ?> val = a.getValue(m);
                    AnnotationValue<?, ?> def = m.getDefaultValue();
                    if (def == null || !def.equals(val)) {
                        sj.add(m.getName() + "=" + val);
                    }
                });
                if (sj.length() > 0) {
                    wasParams[0] = true;
                    sb.append('(');
                    sb.append(sj);
                    sb.append(')');
                }
            }
            if (sb.length() > 0) {
                anns.add(colorize(sb.toString(), color));
            }
        });
        return anns.size() == 0 ? "" : (String.join(wasParams[0] ? "\n" : " ", anns) + "\n");
    }

    static StringBuilder dumpFields(TypeDescription td) {
        StringBuilder sb = new StringBuilder();
        td.getDeclaredFields().forEach(f -> {
            sb.append(formatAnnotations(f));
            sb.append(formatModifiers(f));
            sb.append(f.getType().getTypeName()).append(" ");
            sb.append(f.getName());
            sb.append('\n');
        });
        return sb;
    }

    static StringBuilder dumpMethods(TypeDescription td) {
        StringBuilder sb = new StringBuilder();

        td.getDeclaredMethods().forEach(m -> {
            sb.append(formatAnnotations(m));
            sb.append(formatModifiers(m));

            // Type mt = Type.getMethodType(m.getDescriptor());
            //
            // if (!m.isConstructor()) {
            //     sb.append(mt.getReturnType().getClassName())
            //         .append(' ');
            // }

            sb.append(m.isConstructor() ? "<init>" : m.getName());
            sb.append('(');

            // Type[] args = mt.getArgumentTypes();
            // for (int i = 0; i < args.length; i++) {
            //     if (i != 0) sb.append(", ");
            //     sb.append(args[i].getClassName());
            // }

            sb.append(')').append('\n');
        });

        return sb;
    }

    static Set<String> seenTypes = new HashSet<>();

    // td.getTypeName(), td.getInterfaces(), td.getDeclaredMethods(), etc.
    // — all without Class.forName / classloader involvement
    static void dumpType(TypeDescription td) {
        if (seenTypes.contains(td.getName())) return;
        seenTypes.add(td.getName());

        StringBuilder sb = new StringBuilder();
        sb.append(formatAnnotations(td));
        sb.append(formatModifiers(td));
        sb.append(td);
        sb.append('\n');

        sb.append(indent(dumpFields(td)));
        sb.append(indent(dumpMethods(td)));

        System.out.println(sb.toString());
    }
*/
