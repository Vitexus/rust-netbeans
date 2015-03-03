/*
 * Copyright (C) 2015 drrb
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.github.drrb.rust.netbeans.cargo;

import com.github.drrb.rust.netbeans.parsing.RustLexUtils;
import com.github.drrb.rust.netbeans.parsing.RustTokenId;
import com.github.drrb.rust.netbeans.rustbridge.RustCrateType;
import com.github.drrb.rust.netbeans.util.GsfUtilitiesHack;
import com.google.common.collect.Iterables;
import com.moandjiezana.toml.Toml;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.text.Document;
import org.netbeans.api.lexer.TokenSequence;
import org.openide.filesystems.FileObject;

/**
 *
 */
public class CargoConfig {
    private static final Logger LOG = Logger.getLogger(CargoConfig.class.getName());
    private final FileObject baseDir;

    public CargoConfig(FileObject baseDir) {
        this.baseDir = baseDir;
    }

    public Crate getOwningCrate(FileObject sourceFile) {
        for (Crate crate : getCrates()) {
            for (FileObject modFile : modFiles(crate.getFile())) {
                if (modFile.equals(sourceFile)) {
                    return crate;
                }
            }
        }
        return new Crate(RustCrateType.RLIB, sourceFile);
    }

    private Iterable<? extends FileObject> modFiles(FileObject sourceFile) {
        final List<String> modDeclarations = new LinkedList<>();
        final Document document = GsfUtilitiesHack.getDocument(sourceFile, true); //TODO: why do we need this hack? Why does NbDocument.get not work in tests
        document.render(new Runnable() { //TODO: apparently render() isn't read-locking the document (accoring to the test logs)

            @Override
            public void run() {
                TokenSequence<RustTokenId> rustTokens = new RustLexUtils().getRustTokenSequence(document, 0);
                lookingForModDeclarations:
                while (rustTokens.moveNext()) {
                    if (rustTokens.token().id() == RustTokenId.MOD && rustTokens.moveNext()) {
                        if (rustTokens.moveNext() && rustTokens.token().id() == RustTokenId.OPEN_BRACE) {
                            // It's a mod literal
                            continue lookingForModDeclarations;
                        } else {
                            modDeclarations.add(rustTokens.token().text().toString());
                        }
                    }
                }
            }
        });
        List<Iterable<? extends FileObject>> modFiles = new LinkedList<>();
        modFiles.add(Collections.singleton(sourceFile));
        for (String modName : modDeclarations) {
            FileObject parentDir = sourceFile.getParent();
            FileObject possibleFileMod = parentDir.getFileObject(modName + ".rs");
            FileObject possibleModDir = parentDir.getFileObject(modName);
            FileObject possibleDirMod = possibleModDir == null ? null : possibleModDir.getFileObject("mod.rs");
            FileObject modFile = possibleFileMod == null ? possibleDirMod : possibleFileMod;
            if (modFile == null) {
                LOG.log(Level.WARNING, "Couldn''t find module ''{0}'' (found ''mod {0}'' in {1} but couldn''t find either ''{2}/{0}.rs'' or ''{2}/{0}/mod.rs'')", new Object[]{modName, sourceFile.getPath(), parentDir.getPath()});
                continue;
            }
            modFiles.add(modFiles(modFile));
        }
        return Iterables.concat(modFiles);
    }

    public List<Crate> getCrates() {
        //TODO: there could be errors in this config. Make sure we fail nicely
        Toml cargoConfig = getCargoConfig();

        List<Crate> crates = new LinkedList<>();
        Toml libCrate = cargoConfig.getTable("lib");
        String libCratePath = libCrate.getString("path");
        if (libCratePath != null) {
            FileObject crateFile = baseDir.getFileObject(libCratePath);
            List<String> libCrateTypes = new LinkedList<>(libCrate.getList("crate-type", String.class));
            if (libCrateTypes.isEmpty()) {
                libCrateTypes.add("rlib");
            }
            for (String libCrateType : libCrateTypes) {
                crates.add(new Crate(RustCrateType.forCargoName(libCrateType), crateFile));
            }
        }

        List<Toml> binCrates = cargoConfig.getTables("bin");
        for (Toml binCrate : binCrates) {
            String cratePath = binCrate.getString("path");
            FileObject crateFile = baseDir.getFileObject(cratePath);
            crates.add(new Crate(RustCrateType.EXECUTABLE, crateFile));
        }
        return crates;
    }

    public String getPackageName() {
        Toml cargoConf = getCargoConfig();
        return cargoConf.getTable("package").getString("name");
    }

    private Toml getCargoConfig() {
        FileObject cargoFile = baseDir.getFileObject("Cargo.toml");
        if (cargoFile == null) {
            return new Toml();
        }
        try {
            return new Toml().parse(new InputStreamReader(cargoFile.getInputStream(), UTF_8));
        } catch (FileNotFoundException ex) {
            LOG.log(Level.WARNING, "Couldn't read crates from Cargo.toml", ex);
            return new Toml();
        }
    }
}
