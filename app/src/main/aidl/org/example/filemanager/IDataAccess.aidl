package org.example.filemanager;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IDataAccess {
    Bundle stat(String path) = 0;
    Bundle list(String path, int offset) = 1;
    ParcelFileDescriptor open(String path, String mode) = 2;
    String create(String parent, String name, boolean directory) = 3;
    String rename(String path, String name) = 4;
    void delete(String path) = 5;
    void destroy() = 16777114;
}
