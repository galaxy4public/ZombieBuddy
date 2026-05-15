package me.zed_0xff.zombie_buddy.jardump;

import me.zed_0xff.zombie_buddy.Logger;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CompactTable {
    private final int            m_numCols;
    private final ArrayList<Row> m_rows               = new ArrayList<>();
    private int                  m_minDelta           = 8;
    private int                  m_minAdjacentSpacing = 2; // min spaces between (LEFT, RIGHT) adjacent pair; <=0 disables
    private Align[]              m_aligns;

    public enum Align { DEFAULT, LEFT, RIGHT }

    public static final int ALL_COLS = -1;

    public CompactTable(int numCols) {
        m_numCols = numCols;
        m_aligns = new Align[numCols];
        Arrays.fill(m_aligns, Align.DEFAULT);
    }

    public CompactTable setMinDelta(int minDelta) {
        this.m_minDelta = minDelta;
        return this;
    }

    public CompactTable setMinAdjacentSpacing(int spacing) {
        this.m_minAdjacentSpacing = spacing;
        return this;
    }

    public CompactTable setAlign(int col, Align align) {
        if ( col == ALL_COLS ) {
            Arrays.fill(m_aligns, align);
        } else {
            if (col >= 0 && col < m_numCols) {
                m_aligns[col] = align;
            } else {
                Logger.warn("CompactTable.setAlign: invalid column index " + col);
            }
        }
        return this;
    }

    record Row(String[] cols, int[] lens) {
        String toString(int[] colWidths, Align[] aligns) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.length - 1; i++) {
                int w = colWidths[i];
                if (Utils.isBlank(cols[i])) {
                    sb.append(" ".repeat(w + 1));
                } else if (aligns[i] == Align.RIGHT) {
                    sb.append(" ".repeat(Math.max(0, w - lens[i]))).append(cols[i]).append(" ");
                } else {
                    sb.append(cols[i]).append(" ".repeat(Math.max(0, w - lens[i]))).append(" ");
                }
            }
            sb.append(cols[cols.length - 1]);
            return sb.toString();
        }

        @Override
        public String toString() {
            return Stream.of(cols).filter(s -> !Utils.isBlank(s)).collect(Collectors.joining(" "));
        }
    }

    public ArrayList<Row> rows() { return m_rows; }

    public void addRow(String... cols) {
        if (cols.length != m_numCols) {
            Logger.warn("CompactTable.addRow: expected " + m_numCols + " cols, got " + cols.length);
            if (cols.length < m_numCols) {
                String[] padded = new String[m_numCols];
                System.arraycopy(cols, 0, padded, 0, cols.length);
                for (int i = cols.length; i < m_numCols; i++) padded[i] = "";
                cols = padded;
            } else {
                String[] merged = new String[m_numCols];
                System.arraycopy(cols, 0, merged, 0, m_numCols - 1);
                merged[m_numCols - 1] = Stream.of(cols).skip(m_numCols - 1).collect(Collectors.joining(" "));
                cols = merged;
            }
        }
        int[] lens = new int[cols.length - 1];
        for (int i = 0; i < lens.length; i++) {
            lens[i] = CLIUtil.uncolorize(cols[i]).lines().mapToInt(String::length).max().orElse(0);
        }
        m_rows.add(new Row(cols, lens));
    }

    public String toString() {
        if (m_rows.isEmpty()) return "";

        int n = m_numCols - 1;
        int[] colWidths = new int[n];
        for (int j = 0; j < n; j++) {
            final int col = j;
            // col j's width is determined only by rows that have non-blank content in some earlier col
            // (rows that are blank in all earlier cols are "sparse" and shouldn't inflate later cols)
            final int limit = j;
            java.util.stream.Stream<Row> filtered = j == 0 ? m_rows.stream()
                : m_rows.stream().filter(r -> { for (int k = 0; k < limit; k++) if (!Utils.isBlank(r.cols()[k])) return true; return false; });
            if (m_minDelta == 0 || m_rows.size() <= 1) {
                colWidths[j] = filtered.mapToInt(r -> r.lens()[col]).max().orElse(0);
            } else {
                int[] lens = filtered.mapToInt(r -> r.lens()[col]).sorted().toArray();
                if (lens.length < 2) {
                    colWidths[j] = lens.length == 1 ? lens[0] : 0;
                } else {
                    int delta = lens[lens.length - 1] - lens[lens.length - 2];
                    colWidths[j] = delta < m_minDelta ? lens[lens.length - 1] : lens[lens.length - 2];
                }
            }
        }

        // Remove minimum combined padding from adjacent (non-RIGHT, RIGHT) column pairs
        if (m_minAdjacentSpacing > 0) {
            for (int i = 0; i < n - 1; i++) {
                if (m_aligns[i] == Align.RIGHT || m_aligns[i + 1] != Align.RIGHT) continue;
                final int ci = i, ci1 = i + 1;
                int minSurplus = m_rows.stream()
                    .filter(r -> {
                        if (Utils.isBlank(r.cols()[ci]) || Utils.isBlank(r.cols()[ci1])) return false;
                        for (int k = 0; k < n; k++) if (!Utils.isBlank(r.cols()[k]) && r.lens()[k] > colWidths[k]) return false;
                        return true;
                    })
                    .mapToInt(r -> (colWidths[ci] - r.lens()[ci]) + (colWidths[ci1] - r.lens()[ci1]))
                    .min().orElse(0);
                int removal = minSurplus - (m_minAdjacentSpacing - 1);
                if (removal > 0) colWidths[ci1] -= removal;
            }
        }

        return m_rows.stream().map(r -> {
            for (int j = 0; j < n; j++) {
                if (!Utils.isBlank(r.cols()[j]) && r.lens()[j] > colWidths[j]) {
                    // render cols 0..j-1 normally, then trim trailing space for RIGHT-aligned overflow
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < j; k++) {
                        int w = colWidths[k];
                        if (Utils.isBlank(r.cols()[k])) {
                            sb.append(" ".repeat(w + 1));
                        } else if (m_aligns[k] == Align.RIGHT) {
                            sb.append(" ".repeat(Math.max(0, w - r.lens()[k]))).append(r.cols()[k]).append(" ");
                        } else {
                            sb.append(r.cols()[k]).append(" ".repeat(Math.max(0, w - r.lens()[k]))).append(" ");
                        }
                    }
                    if (m_aligns[j] == Align.RIGHT) sb.setLength(Math.max(0, sb.length() - (r.lens()[j] - colWidths[j])));
                    java.util.List<String> rest = new ArrayList<>();
                    for (int k = j; k < r.cols().length; k++) if (!Utils.isBlank(r.cols()[k])) rest.add(r.cols()[k]);
                    sb.append(String.join(" ", rest));
                    return sb.toString();
                }
            }
            return r.toString(colWidths, m_aligns);
        }).collect(Collectors.joining("\n")) + "\n";
    }
}
