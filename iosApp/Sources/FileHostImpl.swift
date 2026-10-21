import UIKit
import UniformTypeIdentifiers
import XnavKit

/**
 Map packs into the app, for the Kotlin side's PackImport (data/PackImport.kt):
 from Settings' button through the Files picker, or a .navipack opened with
 "Open in xnav.k" (XnavApp's onOpenURL). Either way the file is read where it
 lies, not copied - a pack is 200 MB - so its security-scoped access is held
 here from the moment it arrives until Kotlin says it has read it all.
 */
final class FileHostImpl: NSObject, FileHost, UIDocumentPickerDelegate {
    static let shared = FileHostImpl()

    /** Files being read, by the path Kotlin was given. Main thread only. */
    private var held: [String: URL] = [:]

    func pickPack() {
        // Any file, as the Android app's picker: a type filter that does not
        // match leaves her looking at an empty picker, and the Kotlin side
        // says in words when a file is not a pack.
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: false)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        Self.top()?.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let url = urls.first { open(url) }
    }

    /** A pack for the app, however it came. Main thread. */
    func open(_ url: URL) {
        guard url.isFileURL else { return }
        if url.startAccessingSecurityScopedResource() { held[url.path] = url }
        MainViewControllerKt.OpenPack(path: url.path)
    }

    func releasePack(path: String) {
        DispatchQueue.main.async {
            self.held.removeValue(forKey: path)?.stopAccessingSecurityScopedResource()
        }
    }

    /** The controller on top, to present the picker over whatever is showing. */
    private static func top() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var top = window?.rootViewController
        while let shown = top?.presentedViewController { top = shown }
        return top
    }
}
