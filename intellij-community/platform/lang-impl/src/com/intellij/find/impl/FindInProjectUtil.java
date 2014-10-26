/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.find.impl;

import com.intellij.BundleBase;
import com.intellij.find.*;
import com.intellij.find.findInProject.FindInProjectManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressWrapper;
import com.intellij.openapi.progress.util.TooManyUsagesStatus;
import com.intellij.openapi.project.DumbServiceImpl;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Factory;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileProvider;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import com.intellij.psi.*;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.ui.content.Content;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewManager;
import com.intellij.usages.ConfigurableUsageTarget;
import com.intellij.usages.FindUsagesProcessPresentation;
import com.intellij.usages.UsageViewPresentation;
import com.intellij.util.Function;
import com.intellij.util.PatternUtil;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.regex.Pattern;

public class FindInProjectUtil {
  private static final int USAGES_PER_READ_ACTION = 100;

  private FindInProjectUtil() {}

  public static void setDirectoryName(@NotNull FindModel model, @NotNull DataContext dataContext) {
    PsiElement psiElement = null;
    Project project = CommonDataKeys.PROJECT.getData(dataContext);

    if (project != null && !DumbServiceImpl.getInstance(project).isDumb()) {
      try {
        psiElement = CommonDataKeys.PSI_ELEMENT.getData(dataContext);
      } catch (IndexNotReadyException ignore) {}
    }


    String directoryName = null;

    if (psiElement instanceof PsiDirectory) {
      directoryName = ((PsiDirectory)psiElement).getVirtualFile().getPresentableUrl();
    }

    if (directoryName == null && psiElement instanceof PsiDirectoryContainer) {
      final PsiDirectory[] directories = ((PsiDirectoryContainer)psiElement).getDirectories();
      directoryName = directories.length == 1 ? directories[0].getVirtualFile().getPresentableUrl():null;
    }

    Module module = LangDataKeys.MODULE_CONTEXT.getData(dataContext);
    if (module != null) {
      model.setModuleName(module.getName());
    }

    Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
    if (model.getModuleName() == null || editor == null) {
      model.setDirectoryName(directoryName);
      model.setProjectScope(directoryName == null && module == null && !model.isCustomScope() || editor != null);

      // for convenience set directory name to directory of current file, note that we doesn't change default projectScope
      if (directoryName == null) {
        VirtualFile virtualFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
        if (virtualFile != null && !virtualFile.isDirectory()) virtualFile = virtualFile.getParent();
        if (virtualFile != null) model.setDirectoryName(virtualFile.getPresentableUrl());
      }
    }
  }

  @Nullable
  public static PsiDirectory getPsiDirectory(@NotNull final FindModel findModel, @NotNull Project project) {
    String directoryName = findModel.getDirectoryName();
    if (findModel.isProjectScope() || StringUtil.isEmpty(directoryName)) {
      return null;
    }

    final PsiManager psiManager = PsiManager.getInstance(project);
    String path = directoryName.replace(File.separatorChar, '/');
    VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(path);
    if (virtualFile == null || !virtualFile.isDirectory()) {
      virtualFile = null;
      for (LocalFileProvider provider : ((VirtualFileManagerEx)VirtualFileManager.getInstance()).getLocalFileProviders()) {
        VirtualFile file = provider.findLocalVirtualFileByPath(path);
        if (file != null && file.isDirectory()) {
          if (file.getChildren().length > 0) {
            virtualFile = file;
            break;
          }
          if(virtualFile == null){
             virtualFile = file;
          }
        }
      }
    }
    return virtualFile == null ? null : psiManager.findDirectory(virtualFile);
  }


  @Nullable
  public static Pattern createFileMaskRegExp(@Nullable String filter) {
    if (filter == null) {
      return null;
    }
    String pattern;
    final List<String> strings = StringUtil.split(filter, ",");
    if (strings.size() == 1) {
      pattern = PatternUtil.convertToRegex(filter.trim());
    }
    else {
      pattern = StringUtil.join(strings, new Function<String, String>() {
        @NotNull
        @Override
        public String fun(@NotNull String s) {
          return "(" + PatternUtil.convertToRegex(s.trim()) + ")";
        }
      }, "|");
    }
    return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
  }

  public static void findUsages(@NotNull FindModel findModel,
                                final PsiDirectory psiDirectory,
                                @NotNull final Project project,
                                @NotNull final Processor<UsageInfo> consumer,
                                @NotNull FindUsagesProcessPresentation processPresentation) {
    new FindInProjectTask(findModel, project, psiDirectory).findUsages(consumer, processPresentation);
  }

  static int processUsagesInFile(@NotNull final PsiFile psiFile,
                                         @NotNull final FindModel findModel,
                                         @NotNull final Processor<UsageInfo> consumer) {
    if (findModel.getStringToFind().isEmpty()) {
      if (!ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
              @Override
              public Boolean compute() {
                return consumer.process(new UsageInfo(psiFile,0,0,true));
              }
            })) {
        throw new ProcessCanceledException();
      }
      return 1;
    }
    final VirtualFile virtualFile = psiFile.getVirtualFile();
    if (virtualFile == null) return 0;
    if (virtualFile.getFileType().isBinary()) return 0; // do not decompile .class files
    final Document document = ApplicationManager.getApplication().runReadAction(new Computable<Document>() {
      @Override
      public Document compute() {
        return virtualFile.isValid() ? FileDocumentManager.getInstance().getDocument(virtualFile) : null;
      }
    });
    if (document == null) return 0;
    final int[] offset = {0};
    int count = 0;
    int found;
    ProgressIndicator indicator = ProgressWrapper.unwrap(ProgressManager.getInstance().getProgressIndicator());
    TooManyUsagesStatus tooManyUsagesStatus = TooManyUsagesStatus.getFrom(indicator);
    do {
      tooManyUsagesStatus.pauseProcessingIfTooManyUsages(); // wait for user out of read action
      found = ApplicationManager.getApplication().runReadAction(new Computable<Integer>() {
        @Override
        @NotNull
        public Integer compute() {
          if (!psiFile.isValid()) return 0;
          return addToUsages(document, consumer, findModel, psiFile, offset, USAGES_PER_READ_ACTION);
        }
      });
      count += found;
    }
    while (found != 0);
    return count;
  }

  private static int addToUsages(@NotNull Document document, @NotNull Processor<UsageInfo> consumer, @NotNull FindModel findModel,
                                 @NotNull final PsiFile psiFile, @NotNull int[] offsetRef, int maxUsages) {
    int count = 0;
    CharSequence text = document.getCharsSequence();
    int textLength = document.getTextLength();
    int offset = offsetRef[0];

    Project project = psiFile.getProject();

    FindManager findManager = FindManager.getInstance(project);
    while (offset < textLength) {
      FindResult result = findManager.findString(text, offset, findModel, psiFile.getVirtualFile());
      if (!result.isStringFound()) break;

      final SearchScope customScope = findModel.getCustomScope();
      if (customScope instanceof LocalSearchScope) {
        final TextRange range = new TextRange(result.getStartOffset(), result.getEndOffset());
        if (!((LocalSearchScope)customScope).containsRange(psiFile, range)) break;
      }
      UsageInfo info = new FindResultUsageInfo(findManager, psiFile, offset, findModel, result);
      if (!consumer.process(info)){
        throw new ProcessCanceledException();
      }
      count++;

      final int prevOffset = offset;
      offset = result.getEndOffset();

      if (prevOffset == offset) {
        // for regular expr the size of the match could be zero -> could be infinite loop in finding usages!
        ++offset;
      }
      if (maxUsages > 0 && count >= maxUsages) {
        break;
      }
    }
    offsetRef[0] = offset;
    return count;
  }

  @NotNull
  private static String getTitleForScope(@NotNull final FindModel findModel) {
    String scopeName;
    if (findModel.isProjectScope()) {
      scopeName = FindBundle.message("find.scope.project.title");
    }
    else if (findModel.getModuleName() != null) {
      scopeName = FindBundle.message("find.scope.module.title", findModel.getModuleName());
    }
    else if(findModel.getCustomScopeName() != null) {
      scopeName = findModel.getCustomScopeName();
    }
    else {
      scopeName = FindBundle.message("find.scope.directory.title", findModel.getDirectoryName());
    }

    String result = scopeName;
    if (findModel.getFileFilter() != null) {
      result += " "+FindBundle.message("find.scope.files.with.mask", findModel.getFileFilter());
    }

    return result;
  }

  @NotNull
  public static UsageViewPresentation setupViewPresentation(final boolean toOpenInNewTab, @NotNull FindModel findModel) {
    final UsageViewPresentation presentation = new UsageViewPresentation();

    final String scope = getTitleForScope(findModel);
    final String stringToFind = findModel.getStringToFind();
    presentation.setScopeText(scope);
    if (stringToFind.isEmpty()) {
      presentation.setTabText("Files");
      presentation.setToolwindowTitle(BundleBase.format("Files in {0}", scope));
      presentation.setUsagesString("files");
    }
    else {
      presentation.setTabText(FindBundle.message("find.usage.view.tab.text", stringToFind));
      presentation.setToolwindowTitle(FindBundle.message("find.usage.view.toolwindow.title", stringToFind, scope));
      presentation.setUsagesString(FindBundle.message("find.usage.view.usages.text", stringToFind));
      presentation.setUsagesWord(FindBundle.message("occurrence"));
      presentation.setCodeUsagesString(FindBundle.message("found.occurrences"));
    }
    presentation.setOpenInNewTab(toOpenInNewTab);
    presentation.setCodeUsages(false);
    presentation.setUsageTypeFilteringAvailable(true);

    return presentation;
  }

  @NotNull
  public static FindUsagesProcessPresentation setupProcessPresentation(@NotNull final Project project,
                                                                       final boolean showPanelIfOnlyOneUsage,
                                                                       @NotNull final UsageViewPresentation presentation) {
    FindUsagesProcessPresentation processPresentation = new FindUsagesProcessPresentation(presentation);
    processPresentation.setShowNotFoundMessage(true);
    processPresentation.setShowPanelIfOnlyOneUsage(showPanelIfOnlyOneUsage);
    processPresentation.setProgressIndicatorFactory(
      new Factory<ProgressIndicator>() {
        @NotNull
        @Override
        public ProgressIndicator create() {
          return new FindProgressIndicator(project, presentation.getScopeText());
        }
      }
    );
    return processPresentation;
  }

  public static class StringUsageTarget implements ConfigurableUsageTarget, ItemPresentation {
    @NotNull protected final Project myProject;
    @NotNull protected final FindModel myFindModel;

    public StringUsageTarget(@NotNull Project project, @NotNull FindModel findModel) {
      myProject = project;
      myFindModel = findModel;
    }

    @Override
    @NotNull
    public String getPresentableText() {
      UsageViewPresentation presentation = setupViewPresentation(false, myFindModel);
      return presentation.getToolwindowTitle();
    }

    @NotNull
    @Override
    public String getLongDescriptiveName() {
      return getPresentableText();
    }

    @Override
    public String getLocationString() {
      return myFindModel + "!!";
    }

    @Override
    public Icon getIcon(boolean open) {
      return AllIcons.Actions.Menu_find;
    }

    @Override
    public void findUsages() {
      FindInProjectManager.getInstance(myProject).startFindInProject(myFindModel);
    }

    @Override
    public void findUsagesInEditor(@NotNull FileEditor editor) {}
    @Override
    public void highlightUsages(@NotNull PsiFile file, @NotNull Editor editor, boolean clearHighlights) {}

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public boolean isReadOnly() {
      return true;
    }

    @Override
    @Nullable
    public VirtualFile[] getFiles() {
      return null;
    }

    @Override
    public void update() {
    }

    @Override
    public String getName() {
      return myFindModel.getStringToFind().isEmpty() ? myFindModel.getFileFilter() : myFindModel.getStringToFind();
    }

    @Override
    public ItemPresentation getPresentation() {
      return this;
    }

    @Override
    public void navigate(boolean requestFocus) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean canNavigate() {
      return false;
    }

    @Override
    public boolean canNavigateToSource() {
      return false;
    }

    @Override
    public void showSettings() {
      Content selectedContent = UsageViewManager.getInstance(myProject).getSelectedContent(true);
      JComponent component = selectedContent == null ? null : selectedContent.getComponent();
      FindInProjectManager findInProjectManager = FindInProjectManager.getInstance(myProject);
      findInProjectManager.findInProject(DataManager.getInstance().getDataContext(component));
    }

    @Override
    public KeyboardShortcut getShortcut() {
      return ActionManager.getInstance().getKeyboardShortcut("FindInPath");
    }
  }
}