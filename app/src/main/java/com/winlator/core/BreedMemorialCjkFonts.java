package com.winlator.core;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Installs the cjkfonts-compatible Japanese font fallback required by Breed Memorial. */
public final class BreedMemorialCjkFonts {
    private static final String ASSET = "audio_game_dependencies/breed_memorial_unifont.ttf";
    private static final String HAN_ASSET = "audio_game_dependencies/breed_memorial_sourcehansans.ttc";
    private static final String MARKER = "drive_c/WinlatorBreedMemorial/cjkfonts-v2-installed";
    private static final String FONT_NAME = "unifont.ttf";

    private BreedMemorialCjkFonts() {}

    public static boolean provision(Context context, File prefixDir) {
        File marker = new File(prefixDir, MARKER);

        File fontsDir = new File(prefixDir, "drive_c/windows/Fonts");
        File fontFile = new File(fontsDir, FONT_NAME);
        File hanFile = new File(fontsDir, "sourcehansans.ttc");
        if (marker.isFile() && fontFile.isFile() && hanFile.isFile()) return true;
        File registryFile = new File(prefixDir, "user.reg");
        if (!fontsDir.isDirectory() && !fontsDir.mkdirs()) return false;

        try {
            if (fontFile.length() != 12279760) copyAsset(context, ASSET, fontFile);
            if (hanFile.length() != 117244592) copyAsset(context, HAN_ASSET, hanFile);
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(registryFile)) {
                String replacements = "Software\\Wine\\Fonts\\Replacements";
                String[] aliases = {
                        "Meiryo", "Meiryo UI", "MS Gothic", "MS PGothic", "MS Mincho",
                        "MS PMincho", "MS UI Gothic", "Yu Gothic", "Yu Gothic UI",
                        "Yu Mincho", "UD Digi KyoKasho N-R", "UD Digi KyoKasho NK-R",
                        "UD Digi KyoKasho NP-R", "ＭＳ ゴシック", "ＭＳ Ｐゴシック",
                        "ＭＳ 明朝", "ＭＳ Ｐ明朝"
                };
                for (String alias : aliases) registryEditor.setStringValue(replacements, alias, "Unifont");
                for (String alias : new String[]{"SimSun", "NSimSun", "SimHei", "Microsoft YaHei", "MingLiU", "PMingLiU", "Microsoft JhengHei"})
                    registryEditor.setStringValue(replacements, alias, "Source Han Sans");
                for (String alias : new String[]{"Batang", "BatangChe", "Dotum", "DotumChe", "Gulim", "GulimChe", "Gungsuh", "GungsuhChe", "Malgun Gothic"})
                    registryEditor.setStringValue(replacements, alias, "Source Han Sans");
            }
            File parent = marker.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            return marker.createNewFile() || marker.isFile();
        }
        catch (IOException exception) {
            android.util.Log.e("WinlatorBreedMemorial", "Could not provision cjkfonts", exception);
            return false;
        }
    }

    private static void copyAsset(Context context, String assetName, File destination) throws IOException {
        File temporaryFile = new File(destination.getParentFile(), destination.getName()+".new");
        try (InputStream input = context.getAssets().open(assetName);
             FileOutputStream output = new FileOutputStream(temporaryFile)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (!temporaryFile.renameTo(destination)) {
            if (!FileUtils.copy(temporaryFile, destination)) {
                temporaryFile.delete();
                throw new IOException("Could not install " + destination.getName());
            }
            temporaryFile.delete();
        }
    }
}
