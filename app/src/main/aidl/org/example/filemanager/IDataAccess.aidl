package org.example.filemanager;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import org.example.filemanager.IDataListing;

interface IDataAccess {
    Bundle stat(String path) = 0;
    IDataListing list(String path) = 1;
    ParcelFileDescriptor open(String path, String mode) = 2;
    String create(String parent, String name, boolean directory) = 3;
    String rename(String path, String name) = 4;
    void delete(String path) = 5;
    void destroy() = 16777114;
}
