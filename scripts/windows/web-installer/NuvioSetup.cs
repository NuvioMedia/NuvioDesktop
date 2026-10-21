// Nuvio web installer for Windows.
//
// The release workflow publishes this as Nuvio-Windows-x64-Setup.exe under a
// fixed name, so /releases/latest/download/Nuvio-Windows-x64-Setup.exe is a
// link that never goes stale. Each run asks GitHub for the newest release,
// downloads its MSI, checks it against the release's SHA256SUMS.txt and hands
// it to msiexec. It is compiled with the C# 5 compiler that ships with .NET
// Framework 4, so it needs nothing installed beyond Windows itself.

using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("Nuvio Setup")]
[assembly: AssemblyProduct("Nuvio")]
[assembly: AssemblyDescription("Downloads and installs the latest Nuvio release")]

namespace Nuvio.Setup
{
    internal static class Program
    {
        [STAThread]
        private static int Main()
        {
            if (!Environment.Is64BitOperatingSystem)
            {
                MessageBox.Show(
                    "Nuvio requires a 64-bit version of Windows.",
                    SetupForm.WindowTitle,
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return 1;
            }

            // .NET Framework 4.x only negotiates TLS 1.0 by default, which
            // GitHub has refused for years.
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            var form = new SetupForm();
            Application.Run(form);
            return form.ExitCode;
        }
    }

    internal sealed class ReleaseAsset
    {
        public string Name;
        public string DownloadUrl;
        public long Size;
    }

    internal sealed class ResolvedRelease
    {
        public string Tag;
        public ReleaseAsset Msi;
        public ReleaseAsset Checksums;
    }

    internal static class Releases
    {
        // The desktop updater reads the same list (prereleases included) and
        // takes the newest non-draft release, so a fresh install and an
        // in-app update always land on the same build.
        private const string ReleasesUrl =
            "https://api.github.com/repos/NuvioMedia/NuvioDesktop/releases?per_page=10";
        private const string ChecksumsName = "SHA256SUMS.txt";
        private static readonly Regex MsiNamePattern = new Regex(
            @"^Nuvio-Windows-x64-.+\.msi$",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

        public static ResolvedRelease ResolveLatest(WebClient client)
        {
            client.Headers[HttpRequestHeader.Accept] = "application/vnd.github+json";
            var body = client.DownloadString(ReleasesUrl);
            var serializer = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };
            var releases = serializer.DeserializeObject(body) as object[];
            if (releases == null)
            {
                throw new InvalidDataException("GitHub returned an unexpected release list.");
            }

            foreach (var entry in releases)
            {
                var release = entry as Dictionary<string, object>;
                if (release == null || IsTrue(release, "draft")) continue;

                var resolved = new ResolvedRelease { Tag = GetString(release, "tag_name") };
                var assets = release.ContainsKey("assets") ? release["assets"] as object[] : null;
                if (assets == null) continue;

                foreach (var assetEntry in assets)
                {
                    var asset = assetEntry as Dictionary<string, object>;
                    if (asset == null) continue;
                    var parsed = new ReleaseAsset
                    {
                        Name = GetString(asset, "name"),
                        DownloadUrl = GetString(asset, "browser_download_url"),
                        Size = asset.ContainsKey("size") ? Convert.ToInt64(asset["size"]) : 0L,
                    };
                    if (string.IsNullOrEmpty(parsed.Name) || string.IsNullOrEmpty(parsed.DownloadUrl)) continue;

                    if (MsiNamePattern.IsMatch(parsed.Name))
                    {
                        resolved.Msi = parsed;
                    }
                    else if (string.Equals(parsed.Name, ChecksumsName, StringComparison.OrdinalIgnoreCase))
                    {
                        resolved.Checksums = parsed;
                    }
                }

                if (resolved.Msi != null && resolved.Checksums != null) return resolved;
            }

            throw new InvalidDataException("No published Nuvio release has a Windows installer yet.");
        }

        // SHA256SUMS.txt is written by sha256sum: "<hex>  <file name>" per line.
        public static string ExpectedSha256(string checksums, string fileName)
        {
            using (var reader = new StringReader(checksums))
            {
                string line;
                while ((line = reader.ReadLine()) != null)
                {
                    var match = Regex.Match(line.Trim(), @"^([0-9a-fA-F]{64})\s+\*?(.+)$");
                    if (match.Success &&
                        string.Equals(match.Groups[2].Value.Trim(), fileName, StringComparison.OrdinalIgnoreCase))
                    {
                        return match.Groups[1].Value.ToLowerInvariant();
                    }
                }
            }
            throw new InvalidDataException("The release checksums do not list " + fileName + ".");
        }

        private static bool IsTrue(Dictionary<string, object> map, string key)
        {
            object value;
            return map.TryGetValue(key, out value) && value is bool && (bool)value;
        }

        private static string GetString(Dictionary<string, object> map, string key)
        {
            object value;
            return map.TryGetValue(key, out value) ? value as string : null;
        }
    }

    internal sealed class SetupForm : Form
    {
        public const string WindowTitle = "Nuvio Setup";

        private readonly Label statusLabel;
        private readonly ProgressBar progressBar;
        private readonly Button cancelButton;
        private WebClient client;
        private volatile bool cancelled;

        public int ExitCode { get; private set; }

        public SetupForm()
        {
            ExitCode = 1;
            Text = WindowTitle;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = true;
            StartPosition = FormStartPosition.CenterScreen;
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoScaleDimensions = new SizeF(96F, 96F);
            ClientSize = new Size(440, 130);
            Font = SystemFonts.MessageBoxFont;
            try
            {
                Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            }
            catch (ArgumentException)
            {
            }

            statusLabel = new Label
            {
                AutoSize = false,
                Location = new Point(16, 16),
                Size = new Size(408, 36),
                Text = "Looking for the latest version of Nuvio...",
            };
            progressBar = new ProgressBar
            {
                Location = new Point(16, 56),
                Size = new Size(408, 20),
                Style = ProgressBarStyle.Marquee,
                MarqueeAnimationSpeed = 30,
            };
            cancelButton = new Button
            {
                Location = new Point(344, 90),
                Size = new Size(80, 26),
                Text = "Cancel",
            };
            cancelButton.Click += delegate { Close(); };
            CancelButton = cancelButton;

            Controls.Add(statusLabel);
            Controls.Add(progressBar);
            Controls.Add(cancelButton);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            var worker = new Thread(Run) { IsBackground = true, Name = "nuvio-setup" };
            worker.Start();
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            cancelled = true;
            var current = client;
            if (current != null) current.CancelAsync();
            base.OnFormClosing(e);
        }

        private void Run()
        {
            try
            {
                using (client = new WebClient())
                {
                    client.Headers[HttpRequestHeader.UserAgent] = "NuvioSetup";
                    var release = Releases.ResolveLatest(client);
                    if (cancelled) return;

                    client.Headers[HttpRequestHeader.Accept] = "application/octet-stream";
                    var checksums = client.DownloadString(release.Checksums.DownloadUrl);
                    var expectedHash = Releases.ExpectedSha256(checksums, release.Msi.Name);
                    if (cancelled) return;

                    var msiPath = DownloadMsi(release);
                    if (cancelled) return;

                    SetStatus("Verifying the download...", -1);
                    var actualHash = Sha256(msiPath);
                    if (!string.Equals(actualHash, expectedHash, StringComparison.OrdinalIgnoreCase))
                    {
                        File.Delete(msiPath);
                        throw new InvalidDataException(
                            "The downloaded installer does not match the published checksum. Please try again.");
                    }

                    SetStatus("Starting the Nuvio installer...", -1);
                    Process.Start(new ProcessStartInfo
                    {
                        FileName = "msiexec.exe",
                        Arguments = "/i \"" + msiPath + "\"",
                        UseShellExecute = true,
                    });
                    ExitCode = 0;
                    Finish();
                }
            }
            catch (Exception error)
            {
                if (cancelled) return;
                Fail(Describe(error));
            }
            finally
            {
                client = null;
            }
        }

        private string DownloadMsi(ResolvedRelease release)
        {
            var directory = Path.Combine(Path.GetTempPath(), "NuvioSetup");
            Directory.CreateDirectory(directory);
            foreach (var stale in Directory.GetFiles(directory))
            {
                try { File.Delete(stale); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }

            var msiPath = Path.Combine(directory, Path.GetFileName(release.Msi.Name));
            var partPath = msiPath + ".part";
            var label = "Downloading Nuvio " + release.Tag + "...";
            SetStatus(label, 0);

            var done = new ManualResetEvent(false);
            Exception failure = null;
            client.DownloadProgressChanged += delegate(object sender, DownloadProgressChangedEventArgs args)
            {
                var total = args.TotalBytesToReceive > 0 ? args.TotalBytesToReceive : release.Msi.Size;
                var percent = total > 0 ? (int)Math.Min(100L, args.BytesReceived * 100L / total) : -1;
                SetStatus(label + "  " + FormatMegabytes(args.BytesReceived) +
                    (total > 0 ? " of " + FormatMegabytes(total) : ""), percent);
            };
            client.DownloadFileCompleted += delegate(object sender, AsyncCompletedEventArgs args)
            {
                if (args.Cancelled) failure = new OperationCanceledException();
                else if (args.Error != null) failure = args.Error;
                done.Set();
            };
            client.DownloadFileAsync(new Uri(release.Msi.DownloadUrl), partPath);
            done.WaitOne();

            if (failure != null)
            {
                try { File.Delete(partPath); } catch (IOException) { }
                throw failure;
            }

            if (File.Exists(msiPath)) File.Delete(msiPath);
            File.Move(partPath, msiPath);
            return msiPath;
        }

        private static string Sha256(string path)
        {
            using (var stream = File.OpenRead(path))
            using (var sha = SHA256.Create())
            {
                var hash = sha.ComputeHash(stream);
                var builder = new StringBuilder(hash.Length * 2);
                foreach (var b in hash) builder.Append(b.ToString("x2"));
                return builder.ToString();
            }
        }

        private static string FormatMegabytes(long bytes)
        {
            return (bytes / 1048576.0).ToString("0.0") + " MB";
        }

        private static string Describe(Exception error)
        {
            var web = error as WebException;
            if (web != null)
            {
                var response = web.Response as HttpWebResponse;
                if (response != null && (int)response.StatusCode == 403)
                {
                    return "GitHub is limiting requests from this network right now. Please try again in a few minutes.";
                }
                return "Could not reach GitHub to download Nuvio. Check your internet connection and try again.\n\n" +
                    web.Message;
            }
            return error.Message;
        }

        private void SetStatus(string text, int percent)
        {
            Post(delegate
            {
                statusLabel.Text = text;
                if (percent < 0)
                {
                    progressBar.Style = ProgressBarStyle.Marquee;
                }
                else
                {
                    progressBar.Style = ProgressBarStyle.Continuous;
                    progressBar.Value = percent;
                }
            });
        }

        private void Finish()
        {
            Post(delegate
            {
                cancelled = true;
                Close();
            });
        }

        private void Fail(string message)
        {
            Post(delegate
            {
                progressBar.Style = ProgressBarStyle.Continuous;
                progressBar.Value = 0;
                statusLabel.Text = "Setup could not finish.";
                MessageBox.Show(this, message, WindowTitle, MessageBoxButtons.OK, MessageBoxIcon.Error);
                cancelled = true;
                Close();
            });
        }

        private void Post(MethodInvoker action)
        {
            if (IsDisposed || !IsHandleCreated) return;
            try
            {
                BeginInvoke(action);
            }
            catch (InvalidOperationException)
            {
            }
        }
    }
}
