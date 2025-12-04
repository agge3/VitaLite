package com.tonic.services.hotswapper;

import com.google.common.reflect.ClassPath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public class PluginClassLoader extends URLClassLoader {
    private final ClassLoader parent;
    private final JarFile jarFile;
    private final List<JarFile> depJars;

    public PluginClassLoader(File plugin, ClassLoader parent) throws MalformedURLException {
        super(new URL[]{plugin.toURI().toURL()}, null);
        try {
            jarFile = new JarFile(plugin, true, ZipFile.OPEN_READ, JarFile.runtimeVersion());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        this.depJars = loadDepJars(plugin);
        this.parent = parent;
    }

  private static List<JarFile> loadDepJars(File plugin) {
      List<JarFile> deps = new ArrayList<>();
      File dir = plugin.getParentFile();
      File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
      if (jars != null) {
          for (File jar : jars) {
              if (!jar.equals(plugin)) {
                  try {
                      System.out.println("Loading dep JAR: " + jar.getName());
                      deps.add(new JarFile(jar, true, ZipFile.OPEN_READ, JarFile.runtimeVersion()));
                  } catch (IOException e) {
                      System.out.println("Failed to load dep JAR: " + jar.getName());
                  }
              }
          }
      }
      return deps;
  }

    public List<Class<?>> getPluginClasses() throws IOException {
        return getClasses().stream()
            .filter(this::inheritsPluginClass)
            .collect(Collectors.toList());
    }

     public List<Class<?>> getClasses() throws IOException {
         List<Class<?>> classes = new ArrayList<>();
         for (ClassPath.ClassInfo info : ClassPath.from(this).getAllClasses()) {
             if (info.getName().equals("module-info")) {
                 continue;
             }
             try {
                 classes.add(info.load());
             } catch (NoClassDefFoundError e) {
                 System.out.println("Skipping class " + info.getName() + ": " + e.getMessage());
             }
         }
         return classes;
     }

    public boolean inheritsPluginClass(Class<?> clazz) {
        if (clazz.getSuperclass() == null) {
            return false;
        }

        if (clazz.getSuperclass().getName().endsWith(".Plugin")) {
            return true;
        }

        return inheritsPluginClass(clazz.getSuperclass());
    }

 @Override
 public Class<?> loadClass(String name) throws ClassNotFoundException {
     if (name.equals("module-info")) {
         return Object.class;
     }
     try {
         return super.loadClass(name);
     } catch (Throwable ex) {
         try {
             return findClass(name);  // Try our JARs explicitly
         } catch (ClassNotFoundException | LinkageError e) {
             try {
             return parent.loadClass(name);
             } catch (Throwable ex2) {
                 return Object.class;
             }
         }
     }
 }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
		if (name.equals("module-info")) {
			throw new ClassNotFoundException(name);
		}
        String path = name.replace('.', '/').concat(".class");

		System.out.println("findClass: " + name);
        // First: try the plugin JAR
        JarEntry entry = jarFile.getJarEntry(path);
        if (entry != null) {
            try (InputStream in = jarFile.getInputStream(entry)) {
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        // Fallback: try dep JARs
        for (JarFile dep : depJars) {
            entry = dep.getJarEntry(path);
            if (entry != null) {
                try (InputStream in = dep.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    // try next
                }
            }
        }

        throw new ClassNotFoundException(name);
    }

    @Override
    public void close() throws IOException {
        jarFile.close();
        for (JarFile dep : depJars) {
            dep.close();
        }
        super.close();
    }
}
