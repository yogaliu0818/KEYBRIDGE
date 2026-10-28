using System.Buffers.Binary;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Radios;
using Windows.Networking.Sockets;

ApplicationConfiguration.Initialize();
Application.Run(new MainForm());

internal sealed class MainForm : Form
{
    private readonly ReceiverService service;
    private readonly Label status = new();
    private readonly Label trust = new();
    private readonly Label code = new();
    private readonly Button revoke = new();

    public MainForm()
    {
        Text = "KEYBRIDGE 接收程式";
        Width = 560;
        Height = 390;
        MinimumSize = new Size(520, 350);
        StartPosition = FormStartPosition.CenterScreen;
        service = new ReceiverService(SetStatus, SetTrustState, () => Handle);

        var title = new Label { Text = "KEYBRIDGE", AutoSize = true, Font = new Font("Segoe UI", 20, FontStyle.Bold) };
        var explanation = new Label
        {
            Text = "手機按下「傳送」後，文字才會輸入目前有焦點的 Windows 欄位。\n" +
                   "此視窗保持開啟即可接收；程式不會自動輸入手機草稿。",
            AutoSize = true,
            MaximumSize = new Size(480, 60)
        };
        code.AutoSize = true;
        code.Font = new Font("Segoe UI", 15, FontStyle.Bold);
        trust.AutoSize = true;
        status.AutoSize = true;
        status.MaximumSize = new Size(480, 70);
        status.ForeColor = Color.DarkBlue;

        var bluetooth = new Button { Text = "開啟 Windows 藍牙設定", AutoSize = true };
        bluetooth.Click += (_, _) => Process.Start(new ProcessStartInfo("ms-settings:bluetooth") { UseShellExecute = true });
        revoke.Text = "解除信任／重新配對";
        revoke.AutoSize = true;
        revoke.Click += (_, _) =>
        {
            service.RevokeTrust();
            SetStatus("已解除信任。請在手機清除信任後，再用新的代碼配對。\n若手機尚未配對，請先完成 Windows 藍牙配對。", Color.DarkOrange);
        };

        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(24),
            ColumnCount = 1,
            RowCount = 7
        };
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.Controls.Add(title, 0, 0);
        panel.Controls.Add(explanation, 0, 1);
        panel.Controls.Add(code, 0, 2);
        panel.Controls.Add(trust, 0, 3);
        panel.Controls.Add(bluetooth, 0, 4);
        panel.Controls.Add(revoke, 0, 5);
        panel.Controls.Add(status, 0, 6);
        Controls.Add(panel);

        Shown += async (_, _) => await StartReceiverAsync();
        FormClosing += (_, _) => service.Stop();
        SetTrustState(service.IsTrusted);
    }

    private async Task StartReceiverAsync()
    {
        try
        {
            if (!await ReceiverService.IsBluetoothOnAsync())
            {
                SetStatus("找不到已開啟的藍牙無線電。請開啟藍牙後按「開啟 Windows 藍牙設定」，再重新啟動程式。", Color.DarkOrange);
                return;
            }
            await service.StartAsync();
        }
        catch (Exception ex)
        {
            SetStatus($"啟動失敗：{ex.Message}\n請確認藍牙已開啟、手機已配對，並重新啟動程式。", Color.DarkRed);
        }
    }

    private void SetTrustState(bool isTrusted)
    {
        if (IsDisposed) return;
        if (InvokeRequired) { BeginInvoke(() => SetTrustState(isTrusted)); return; }
        code.Text = isTrusted ? "已完成首次配對" : $"首次連線代碼：{service.PairingCode}";
        trust.Text = isTrusted
            ? "這台手機已記住信任；下次啟動會自動驗證。"
            : "第一次使用：手機輸入這組代碼。配對成功後不必再次輸入。";
        revoke.Enabled = isTrusted;
    }

    private void SetStatus(string message) => SetStatus(message, Color.DarkBlue);

    private void SetStatus(string message, Color color)
    {
        if (IsDisposed) return;
        if (InvokeRequired) { BeginInvoke(() => SetStatus(message, color)); return; }
        status.Text = message;
        status.ForeColor = color;
    }
}

internal sealed class ReceiverService
{
    private static readonly Guid ServiceUuid = new("e7d12867-9072-47f6-ae29-c12272ca5b37");
    private readonly Action<string> report;
    private readonly Action<bool> trustChanged;
    private readonly Func<IntPtr> receiverWindow;
    private readonly TrustedDeviceStore store = new();
    private readonly SemaphoreSlim connectionGate = new(1, 1);
    private StreamSocketListener? listener;
    private RfcommServiceProvider? provider;
    private TrustedDevice? trusted;
    private readonly Dictionary<string, byte> recentMessages = new();
    private readonly object recentLock = new();

    public ReceiverService(Action<string> report, Action<bool> trustChanged, Func<IntPtr> receiverWindow)
    {
        this.report = report;
        this.trustChanged = trustChanged;
        this.receiverWindow = receiverWindow;
        trusted = store.Load();
        PairingCode = NewPairingCode();
    }

    public bool IsTrusted => trusted is not null;
    public string PairingCode { get; private set; }

    public static async Task<bool> IsBluetoothOnAsync()
    {
        try
        {
            var radios = await Radio.GetRadiosAsync();
            var bluetooth = radios.FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);
            return bluetooth is not null && bluetooth.State == RadioState.On;
        }
        catch
        {
            return true;
        }
    }

    public async Task StartAsync()
    {
        provider = await RfcommServiceProvider.CreateAsync(RfcommServiceId.FromUuid(ServiceUuid));
        listener = new StreamSocketListener();
        listener.ConnectionReceived += async (_, args) => await HandleConnectionAsync(args.Socket);
        await listener.BindServiceNameAsync(provider.ServiceId.AsString(), SocketProtectionLevel.BluetoothEncryptionWithAuthentication);
        provider.StartAdvertising(listener);
        report(IsTrusted
            ? "藍牙服務已啟動，等待已信任手機自動連線。"
            : "藍牙服務已啟動，等待手機首次配對。請保持此視窗開啟。\n若尚未配對，請先開啟 Windows 藍牙設定。\n注意：程式未經程式碼簽署。");
    }

    public void RevokeTrust()
    {
        trusted = null;
        store.Delete();
        PairingCode = NewPairingCode();
        trustChanged(false);
    }

    public void Stop()
    {
        try { provider?.StopAdvertising(); } catch { }
        listener?.Dispose();
        listener = null;
        provider = null;
    }

    private async Task HandleConnectionAsync(StreamSocket socket)
    {
        if (!await connectionGate.WaitAsync(0)) { socket.Dispose(); return; }
        try
        {
            report("手機已連線，正在驗證…");
            using (socket)
            using (var input = socket.InputStream.AsStreamForRead())
            using (var output = socket.OutputStream.AsStreamForWrite())
            {
                var first = await ReadFrameAsync(input);
                if (first is null) return;
                if (!await AuthenticateAsync(first.Value.Type, first.Value.Payload, output)) return;
                report("手機已連線並完成信任驗證。請在手機確認文字後按「傳送」。");

                while (true)
                {
                    var frame = await ReadFrameAsync(input);
                    if (frame is null || frame.Value.Type != 3 || frame.Value.Payload.Length < 17) break;
                    var idKey = Convert.ToBase64String(frame.Value.Payload, 0, 16);
                    byte result;
                    lock (recentLock)
                    {
                        if (recentMessages.TryGetValue(idKey, out var previous)) result = previous;
                        else result = 255;
                    }
                    if (result == 255)
                    {
                        try { result = Inject(new UTF8Encoding(false, true).GetString(frame.Value.Payload, 16, frame.Value.Payload.Length - 16)); }
                        catch (DecoderFallbackException) { result = 0; }
                        lock (recentLock)
                        {
                            if (recentMessages.Count >= 1024) recentMessages.Remove(recentMessages.Keys.First());
                            recentMessages[idKey] = result;
                        }
                    }
                    await WriteFrameAsync(output, 0x83, new[] { result });
                    report(result switch
                    {
                        1 => "已送出輸入事件；請檢查目標欄位內容。",
                        2 => "只送出部分輸入事件；請先檢查電腦，勿直接重送。",
                        _ => "未送出：請先點選要輸入的文字欄位，不要讓 KEYBRIDGE 接收程式保持焦點。"
                    });
                }
            }
        }
        catch (Exception ex)
        {
            report($"連線中斷：{ex.Message}");
        }
        finally
        {
            connectionGate.Release();
            report("等待手機連線。");
        }
    }

    private async Task<bool> AuthenticateAsync(byte type, byte[] payload, Stream output)
    {
        if (type == 1)
        {
            if (trusted is not null || payload.Length != 54)
            {
                await WriteFrameAsync(output, 0x81, new byte[] { 3 });
                report("手機要求重新配對，但目前仍有已信任裝置；請先按「解除信任」。");
                return false;
            }
            var suppliedPin = payload.AsSpan(0, 6).ToArray();
            if (!CryptographicOperations.FixedTimeEquals(suppliedPin, Encoding.ASCII.GetBytes(PairingCode)))
            {
                await WriteFrameAsync(output, 0x81, new byte[] { 3 });
                report("手機輸入的首次配對代碼不正確。");
                return false;
            }
            var deviceId = payload.AsSpan(6, 16).ToArray();
            var token = RandomNumberGenerator.GetBytes(32);
            store.Save(deviceId, token);
            trusted = new TrustedDevice(deviceId, token);
            trustChanged(true);
            await WriteFrameAsync(output, 0x81, new byte[] { 1 }.Concat(token).ToArray());
            report("首次配對成功；已記住這台手機，之後會自動連線。");
            return true;
        }

        if (type == 2 && trusted is not null && payload.Length == 80)
        {
            var deviceId = payload.AsSpan(0, 16).ToArray();
            var challenge = payload.AsSpan(16, 32).ToArray();
            var suppliedMac = payload.AsSpan(48, 32).ToArray();
            using var hmac = new HMACSHA256(trusted.Token);
            var expectedMac = hmac.ComputeHash(challenge);
            if (deviceId.SequenceEqual(trusted.DeviceId) && CryptographicOperations.FixedTimeEquals(suppliedMac, expectedMac))
            {
                await WriteFrameAsync(output, 0x82, new byte[] { 1 });
                return true;
            }
        }

        await WriteFrameAsync(output, 0x82, new byte[] { 3 });
        report("手機信任驗證失敗；若其中一端已重設，請在兩端解除信任後重新配對。");
        return false;
    }

    private static async Task WriteFrameAsync(Stream output, byte type, byte[] payload)
    {
        if (payload.Length > 1024 * 1024) throw new InvalidDataException("訊息太大");
        var frame = new byte[9 + payload.Length];
        Encoding.ASCII.GetBytes("KB03").CopyTo(frame, 0);
        frame[4] = type;
        BinaryPrimitives.WriteInt32BigEndian(frame.AsSpan(5, 4), payload.Length);
        payload.CopyTo(frame, 9);
        await output.WriteAsync(frame);
        await output.FlushAsync();
    }

    private static async Task<(byte Type, byte[] Payload)?> ReadFrameAsync(Stream input)
    {
        var header = new byte[9];
        if (!await ReadExactAsync(input, header) || !header.AsSpan(0, 4).SequenceEqual("KB03"u8)) return null;
        var length = BinaryPrimitives.ReadInt32BigEndian(header.AsSpan(5, 4));
        if (length < 0 || length > 1024 * 1024) return null;
        var payload = new byte[length];
        return await ReadExactAsync(input, payload) ? (header[4], payload) : null;
    }

    private static async Task<bool> ReadExactAsync(Stream input, byte[] buffer)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var count = await input.ReadAsync(buffer.AsMemory(offset));
            if (count == 0) return false;
            offset += count;
        }
        return true;
    }

    private static string NewPairingCode() => RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");

    private byte Inject(string text)
    {
        text = text.Replace("\r\n", "\n").Replace('\r', '\n');
        const int chunkChars = 128;
        var originalWindow = GetForegroundWindow();
        if (originalWindow == IntPtr.Zero || originalWindow == GetConsoleWindow() || originalWindow == receiverWindow()) return 0;
        var sentAny = false;
        for (var start = 0; start < text.Length; start += chunkChars)
        {
            if (GetForegroundWindow() != originalWindow) return sentAny ? (byte)2 : (byte)0;
            var events = new List<INPUT>();
            foreach (var ch in text.AsSpan(start, Math.Min(chunkChars, text.Length - start)))
            {
                var enter = ch == '\n';
                events.Add(MakeInput(enter ? (ushort)0x0D : (ushort)0, enter ? (ushort)0 : ch, enter ? 0u : 4u));
                events.Add(MakeInput(enter ? (ushort)0x0D : (ushort)0, enter ? (ushort)0 : ch, (enter ? 0u : 4u) | 2u));
            }
            if (events.Count == 0) continue;
            var nativeEvents = events.ToArray();
            var sent = SendInput((uint)nativeEvents.Length, nativeEvents, Marshal.SizeOf<INPUT>());
            if (sent != nativeEvents.Length) return sent > 0 || sentAny ? (byte)2 : (byte)0;
            sentAny = true;
        }
        return 1;
    }

    private static INPUT MakeInput(ushort key, ushort scan, uint flags) => new() { type = 1, U = new InputUnion { ki = new KEYBDINPUT { wVk = key, wScan = scan, dwFlags = flags } } };
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("kernel32.dll")] private static extern IntPtr GetConsoleWindow();
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, INPUT[] inputs, int size);
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion { [FieldOffset(0)] public KEYBDINPUT ki; [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public HARDWAREINPUT hi; }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] private struct HARDWAREINPUT { public uint uMsg; public ushort wParamL, wParamH; }
}

internal sealed record TrustedDevice(byte[] DeviceId, byte[] Token);

internal sealed class TrustedDeviceStore
{
    private readonly string path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KEYBRIDGE", "trusted-device.dat");

    public TrustedDevice? Load()
    {
        try
        {
            var protectedBytes = File.ReadAllBytes(path);
            var plain = ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser);
            if (plain.Length != 52 || !plain.AsSpan(0, 4).SequenceEqual("KBT1"u8)) return null;
            return new TrustedDevice(plain.AsSpan(4, 16).ToArray(), plain.AsSpan(20, 32).ToArray());
        }
        catch { return null; }
    }

    public void Save(byte[] deviceId, byte[] token)
    {
        if (deviceId.Length != 16 || token.Length != 32) throw new ArgumentException("信任資料長度不正確");
        var plain = new byte[52];
        Encoding.ASCII.GetBytes("KBT1").CopyTo(plain, 0);
        deviceId.CopyTo(plain, 4);
        token.CopyTo(plain, 20);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path, ProtectedData.Protect(plain, null, DataProtectionScope.CurrentUser));
    }

    public void Delete()
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
