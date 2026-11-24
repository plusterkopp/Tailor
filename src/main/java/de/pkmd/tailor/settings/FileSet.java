package de.pkmd.tailor.settings;

import java.util.ArrayList;
import java.util.List;

/** A named group of files that are opened together. */
public final class FileSet {

    private String name;
    private List<FileEntry> files = new ArrayList<>();

    public FileSet() {
    }

    public FileSet(String name, List<FileEntry> files) {
        this.name = name;
        this.files = new ArrayList<>(files);
    }

    public FileSet copy() {
        return new FileSet(name, getFiles().stream().map(FileEntry::copy).toList());
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<FileEntry> getFiles() {
        if (files == null) {
            files = new ArrayList<>();
        }
        return files;
    }

    public void setFiles(List<FileEntry> files) {
        this.files = new ArrayList<>(files);
    }

    @Override
    public String toString() {
        return name;
    }
}
