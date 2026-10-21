package com.local.glasshome;

final class InputItem {
    final String label;
    final String id;
    final boolean passthrough;
    final int rank;

    InputItem(String label, String id, boolean passthrough, int rank) {
        this.label = label;
        this.id = id;
        this.passthrough = passthrough;
        this.rank = rank;
    }
}
