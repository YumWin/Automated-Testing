import com.ibm.wala.classLoader.Language;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.AllApplicationEntrypoints;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.SSAPropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.config.AnalysisScopeReader;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * WALA自动化测试类
 * @author WangBo
 * @version 1.0
 */
public class automatedWALA {
    //全局维护两个静态String列表，一个存放依赖类关系，一个存放依赖方法关系，便于递归和方法调用
    private static ArrayList<String> classAns = new ArrayList<String>();
    private static ArrayList<String> methodAns = new ArrayList<String>();

    /**
     * 主程序入口
     * @param args
     * @throws IOException
     * @throws ClassHierarchyException
     * @throws IllegalArgumentException
     * @throws InvalidClassFileException
     * @throws CancelException
     */
    public static void main(String[] args) throws IOException, ClassHierarchyException, IllegalArgumentException, InvalidClassFileException, CancelException {
        //读入targetPath并生成分析域
        String targetPath = args[1];
        AnalysisScope scope = generateScope(targetPath);
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);

        classAns.add("digraph cmd_class {");
        methodAns.add("digraph cmd_method {");
        //通过WALA生成依赖图并递归生成写入dot的list
        AllApplicationEntrypoints entrypoints = new AllApplicationEntrypoints(scope, cha);
        AnalysisOptions option = new AnalysisOptions(scope, entrypoints);
        SSAPropagationCallGraphBuilder builder = Util.makeZeroCFABuilder(
                Language.JAVA, option, new AnalysisCacheImpl(), cha, scope
        );
        CallGraph chaCG = builder.makeCallGraph(option);
        for (CGNode nodeA : chaCG) {
            if (nodeA.getMethod() instanceof ShrikeBTMethod) {
                ShrikeBTMethod method = (ShrikeBTMethod) nodeA.getMethod();
                if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                    getPredNodesClass(nodeA, chaCG);
                    getPredNodesMethod(nodeA, chaCG);
                }
            }
        }
        classAns.add("}");
        methodAns.add("}");
//        写入dot文件（方法调用被注释）
//        forFile("E:\\Automated-Testing\\Report","//class-CMD.dot", classAns);
//        forFile("E:\\Automated-Testing\\Report","//method-CMD.dot", methodAns);
        //生成受影响的类与方法txt
        generateTxt(args[2], args[0]);
    }

    /**
     * 功能：生成分析域scope
     * 步骤：1：先通过scope.txt和exclusion.txt生成基本分析域
     * 2：读取classes中的所有class文件
     * 3：读取test-classes中的所有class文件
     * 4：综合加入scope并返回
     * 备注：需要考虑IOException,InvalidClassFileException,NullPointerException
     *
     * @param targetPath
     */
    private static AnalysisScope generateScope(String targetPath) throws IOException, InvalidClassFileException, NullPointerException {
        AnalysisScope scope = AnalysisScopeReader.readJavaScope("E:\\Automated-Testing\\Project\\src\\main\\resources\\scope.txt", new File("E:\\Automated-Testing\\Project\\src\\main\\resources\\exclusion.txt"), automatedWALA.class.getClassLoader());
        String classesTargetPath = targetPath + "\\classes\\net\\mooctest";
        String testTargetPath = targetPath + "\\test-classes\\net\\mooctest";
        ArrayList<String> fileList = getFile(classesTargetPath);
        fileList.addAll(getFile(testTargetPath));
        for (int i = 0; i < fileList.size(); i++)
            scope.addClassFileToScope(ClassLoaderReference.Application, new File(fileList.get(i)));
        return scope;
    }

    /**
     * 功能：获取path下的所有文件（无递归）
     * 步骤：1：读取path下的所有文件并放入array
     * 2：读取array内所有文件并放入tempList
     * 3：返回tempList
     * 备注：考虑到空文件夹，可能会产生NullPointerException
     *
     * @param path
     */
    private static ArrayList<String> getFile(String path) {
        ArrayList<String> tempList = new ArrayList<String>();
        File file = new File(path);
        File[] array = file.listFiles();

        for (int i = 0; i < array.length; i++) {
            if (array[i].isFile())//如果是文件
            {
                tempList.add(array[i].getPath());
            } else if (array[i].isDirectory())//如果是文件夹
                return null;
        }
        return tempList;
    }

    /**
     * 功能：生成文本txt文件
     * 步骤：1：读取change_Info
     * 2：解析分析粒度
     * 3：生成被选出的方法并返回
     * 备注：无特殊情况
     *
     * @param rootPath
     * @param type
     */
    private static void generateTxt(String rootPath, String type) {
        ArrayList<String> changeRead = readTxtFile(rootPath);
        if (type.equals("-c")) {
            ArrayList<String> classTemp = getChangedClass(changeRead);
            ArrayList<String> classAffected = findAffectedClass(classTemp);
            ArrayList<String> classText = getChangedClassText(classAffected);
            classText.add("");
            forFile(".", "\\selection-class.txt", classText);
//        System.out.println(CompareTxt(rootPath+"\\selection-class.txt",rootPath+"\\selection_class.txt"));
        } else if (type.equals("-m")) {
            ArrayList<String> methodTemp = getChangedMethod(changeRead);
            ArrayList<String> methodTextTemp = getStrongMethodList(methodTemp);
            ArrayList<String> methodText = getChangedMethodText(methodTextTemp);
            methodText.add("");
            forFile(".", "\\selection-method.txt", methodText);
//        System.out.println(CompareTxt(rootPath+"\\selection-method.txt",rootPath+"\\selection_method.txt"));
        }
    }

    /**
     * 功能：得到强联通依赖类（含递归）
     * 步骤：1：读取methodAns
     * 2：解析是否还有未被调出的新依赖，若没有则返回当前依赖类
     * 3：若含有新依赖则返回含有新依赖的类列表递归函数
     * 备注：注意无穷递归
     *
     * @param methodTemp
     */
    private static ArrayList<String> getStrongMethodList(ArrayList<String> methodTemp) {
        for (int i = 1; i < methodAns.size() - 1; i++) {
            String temps = methodAns.get(i);
            for (int j = 0; j < temps.length(); j++) {
                if (temps.charAt(j) == '-') {
                    String tempsL = temps.substring(2, j - 2);
                    String tempsR = temps.substring(j + 4, temps.length() - 2);
                    if (methodTemp.contains(tempsL) && (!(tempsR.contains("Test"))) && (!(methodTemp.contains(tempsR)))) {
                        methodTemp.add(tempsR);
                        return getStrongMethodList(methodTemp);
                    }
                }
            }
        }
        return methodTemp;
    }

    /**
     * 功能：得到改变的方法文本
     * 步骤：1：读取methodAns
     * 2：解析被修改的依赖方法
     * 3：返回一个含有依赖方法的list
     * 备注：注意静态变量的使用
     *
     * @param methodTemp
     */
    private static ArrayList<String> getChangedMethodText(ArrayList<String> methodTemp) {
        ArrayList<String> methodText = new ArrayList<String>();
        for (int i = 1; i < methodAns.size() - 1; i++) {
            String temps = methodAns.get(i);
            for (int j = 0; j < temps.length(); j++) {
                if (temps.charAt(j) == '-') {
                    String tempsL = temps.substring(2, j - 2);
                    String tempsR = temps.substring(j + 4, temps.length() - 2);
                    if (methodTemp.contains(tempsL)) {
                        if ((!methodText.contains(tempsR)) && (!tempsR.contains("<init>")) && ((!tempsR.contains("initialize")) && (tempsR.contains("Test"))))
                            methodText.add(tempsR);
                    }
                }
            }
        }
        return deFormatList(methodText);
    }

    /**
     * 检测两个路径内txt是否相同的方法（无序）
     * @param path
     * @param todoPath
     * @return
     */
    public static boolean CompareTxt(String path, String todoPath) {
        ArrayList<String> testList = readTxtFile(path);
        ArrayList<String> todoList = readTxtFile(todoPath);
        for (int i = 0; i < testList.size(); i++) {
            todoList.remove(todoList.indexOf(testList.get(i)));
        }
        if (todoList.size() == 0)
            return true;
        else {
            for (int j = 0; j < todoList.size(); j++)
                System.out.println(todoList.get(j));
            return false;
        }

    }

    /**
     * 格式化得到的列表
     * @param classAffected
     * @return
     */
    private static ArrayList<String> formatList(ArrayList<String> classAffected) {
        ArrayList<String> tempList = new ArrayList<String>();
        for (int i = 0; i < classAffected.size(); i++) {
            String temps = classAffected.get(i);
            temps = temps.substring(1);
            temps = temps.replace('/', '.');
            tempList.add(temps);
        }
        return tempList;
    }

    /**
     * 去格式化得到的列表（使用于方法粒度）
     * @param methodText
     * @return
     */
    private static ArrayList<String> deFormatList(ArrayList<String> methodText) {
        ArrayList<String> tempList = new ArrayList<String>();
        for (int i = 0; i < methodText.size(); i++) {
            String temps = methodText.get(i);
            for (int j = temps.length() - 1; j >= 0; j--) {
                if (temps.charAt(j) == '.') {
                    String toBeAdd = ("L" + temps.substring(0, j)).replace('.', '/');
                    tempList.add(toBeAdd + " " + temps);
                    break;
                }
            }
        }
        return tempList;
    }
    /**
     * 功能：得到改变的类文本
     * 步骤：1：读取methodAns
     * 2：解析被修改的依赖类
     * 3：返回一个含有格式化好的依赖类的list
     * 备注：注意静态变量的使用
     *
     * @param classAffected
     */
    private static ArrayList<String> getChangedClassText(ArrayList<String> classAffected) {
        ArrayList<String> classFormat = formatList(classAffected);
        ArrayList<String> classText = new ArrayList<String>();
        for (int i = 1; i < methodAns.size() - 1; i++) {
            String methodAffectedMethod = methodAns.get(i);
            int tempj = 0;
            boolean firstSear = true;
            for (int j = methodAffectedMethod.length() - 1; j >= 0; j--) {
                if (firstSear && methodAffectedMethod.charAt(j) == '.') {
                    tempj = j;
                    firstSear = false;
                }
                if ((!firstSear) && methodAffectedMethod.charAt(j) == '"') {
                    if (classFormat.contains(methodAffectedMethod.substring(j + 1, tempj))) {
                        int tempI = classFormat.indexOf(methodAffectedMethod.substring(j + 1, tempj));
                        String toBeAdd = classAffected.get(tempI) + " " + methodAffectedMethod.substring(j + 1, methodAffectedMethod.length() - 2);
                        if ((!classText.contains(toBeAdd)) && (!toBeAdd.contains("<init>")) && (!toBeAdd.contains("initialize")))
                            classText.add(toBeAdd);
                    }
                }

            }
        }
        return classText;
    }

    /**
     * 找到受影响的所有类
     * @param classTemp
     * @return
     */
    private static ArrayList<String> findAffectedClass(ArrayList<String> classTemp) {
        ArrayList<String> classAffected = new ArrayList<String>();
        for (int i = 1; i < classAns.size() - 1; i++) {
            String classAffectedClass = classAns.get(i);
            for (int j = 0; j < classAffectedClass.length(); j++)
                if ((classAffectedClass.charAt(j) == '-') && (classTemp.contains(classAffectedClass.substring(2, j - 2))) && (!classAffected.contains(classAffectedClass.substring(2, j - 2))) && (classAffectedClass.substring(j + 4, classAffectedClass.length() - 2)).contains("Test"))
                    classAffected.add(classAffectedClass.substring(j + 4, classAffectedClass.length() - 2));
        }
        return classAffected;
    }

    /**
     * 得到改变的类列表
     * @param changeRead
     * @return
     */
    private static ArrayList<String> getChangedClass(ArrayList<String> changeRead) {
        ArrayList<String> classTemp = new ArrayList<String>();
        for (int i = 0; i < changeRead.size(); i++) {
            String change = changeRead.get(i);
            for (int j = 0; j < change.length(); j++) {
                if (change.charAt(j) == ' ')
                    if (!classTemp.contains(change.substring(0, j)))
                        classTemp.add(change.substring(0, j));
            }
        }
        return classTemp;
    }

    /**
     * 得到改变的方法列表
     * @param changeRead
     * @return
     */
    private static ArrayList<String> getChangedMethod(ArrayList<String> changeRead) {
        ArrayList<String> classTemp = new ArrayList<String>();
        for (int i = 0; i < changeRead.size(); i++) {
            String change = changeRead.get(i);
            for (int j = 0; j < change.length(); j++) {
                if (change.charAt(j) == ' ')
                    if (!classTemp.contains(change.substring(j + 1)))
                        classTemp.add(change.substring(j + 1));
            }
        }
        return classTemp;
    }

    /**
     * 功能：Java读取txt文件的内容
     * 步骤：1：先获得文件句柄
     * 2：获得文件句柄当做是输入一个字节码流，需要对这个输入流进行读取
     * 3：读取到输入流后，需要读取生成字节流
     * 4：一行一行的输出。readline()。
     * 备注：需要考虑的是异常情况
     *
     * @param filePath
     */
    private static ArrayList<String> readTxtFile(String filePath) {
        ArrayList<String> arrayList = new ArrayList<String>();
        try {
            String encoding = "GBK";
            File file = new File(filePath);
            if (file.isFile() && file.exists()) { //判断文件是否存在
                InputStreamReader read = new InputStreamReader(
                        new FileInputStream(file), encoding);//考虑到编码格式
                BufferedReader bufferedReader = new BufferedReader(read);
                String lineTxt = null;
                while ((lineTxt = bufferedReader.readLine()) != null) {
                    ;
                    arrayList.add(lineTxt);
                }
                read.close();
            } else {
                System.out.println("找不到指定的文件");
            }
        } catch (Exception e) {
            System.out.println("读取文件内容出错");
            e.printStackTrace();
        }
        return arrayList;
    }

    /**
     * 生成file方法（本例中是生成dot和txt文件）
     * @param rootPath
     * @param extraPath
     * @param content
     */
    private static void forFile(String rootPath, String extraPath, ArrayList<String> content) {
        String path = rootPath;
        File file = new File(path);
        if (!file.exists()) {
            file.mkdirs();
        }
        String writeFilePath = path + extraPath;
        File writeFile = new File(writeFilePath);
        if (!writeFile.exists()) {
            try {
                writeFile.createNewFile();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(writeFilePath));
            for (int i = 0; i < content.size(); i++) {
                String s = content.get(i);
                pw.println(s);

            }
            pw.flush();
            pw.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * 功能：得到前继类（含递归）
     * 步骤：1：读取当前结点
     * 2：遍历当前节点周遭结点，若无节点则返回
     * 3：若还含有结点则递归新结点
     * 备注：深度优先遍历图，注意无穷递归和静态变量
     *
     * @param node
     * @param chaCG
     */
    private static void getPredNodesClass(CGNode node, CallGraph chaCG) {
        String classInnerName = "";
        String classInnerNameN = "";
        if (node.getMethod() instanceof ShrikeBTMethod) {
            ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
            if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                classInnerName = method.getDeclaringClass().getName().toString();
            } else
                return;
        } else
            return;
        Iterator<CGNode> iter = chaCG.getPredNodes(node);

        while (iter.hasNext()) {
            CGNode nodeN = iter.next();
            if (nodeN.getMethod() instanceof ShrikeBTMethod) {
                ShrikeBTMethod methodN = (ShrikeBTMethod) nodeN.getMethod();
                if ("Application".equals(methodN.getDeclaringClass().getClassLoader().toString())) {
                    classInnerNameN = methodN.getDeclaringClass().getName().toString();
                    if (!classAns.contains("\t\"" + classInnerName + '"' + " -> " + '"' + classInnerNameN + '"' + ';')) {
                        classAns.add(("\t\"" + classInnerName + '"' + " -> " + '"' + classInnerNameN + '"' + ';'));
                    }
                } else
                    continue;
            } else
                continue;
            if (!classInnerName.equals(classInnerNameN))
                getPredNodesClass(nodeN, chaCG);
        }
    }

    /**
     * 功能：得到前继方法（含递归）
     * 步骤：1：读取当前结点
     * 2：遍历当前节点周遭结点，若无节点则返回
     * 3：若还含有结点则递归新结点
     * 备注：深度优先遍历图，注意无穷递归和静态变量
     *
     * @param node
     * @param chaCG
     */
    private static void getPredNodesMethod(CGNode node, CallGraph chaCG) {
        String signature = "";
        String signatureN = "";
        if (node.getMethod() instanceof ShrikeBTMethod) {
            ShrikeBTMethod method = (ShrikeBTMethod) node.getMethod();
            if ("Application".equals(method.getDeclaringClass().getClassLoader().toString())) {
                signature = method.getSignature();
            } else
                return;
        } else
            return;
        Iterator<CGNode> iter = chaCG.getPredNodes(node);

        while (iter.hasNext()) {
            CGNode nodeN = iter.next();
            if (nodeN.getMethod() instanceof ShrikeBTMethod) {
                ShrikeBTMethod methodN = (ShrikeBTMethod) nodeN.getMethod();
                if ("Application".equals(methodN.getDeclaringClass().getClassLoader().toString())) {
                    signatureN = methodN.getSignature();
                    if (!methodAns.contains(("\t\"" + signature + '"' + " -> " + '"' + signatureN + '"' + ';'))) {
                        methodAns.add(("\t\"" + signature + '"' + " -> " + '"' + signatureN + '"' + ';'));
                    }
                } else
                    continue;
            } else
                continue;
            if (!signature.equals(signatureN))
                getPredNodesMethod(nodeN, chaCG);
        }
    }
}


