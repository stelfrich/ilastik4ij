package org.ilastik.ilastik4ij.hdf5;

import static ch.systemsx.cisd.hdf5.hdf5lib.H5D.H5Dclose;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5D.H5Dcreate;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5D.H5Dget_space;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5D.H5Dset_extent;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5D.H5Dwrite;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5F.H5Fclose;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5F.H5Fcreate;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5P.H5Pclose;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5P.H5Pcreate;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5P.H5Pset_chunk;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5P.H5Pset_deflate;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5S.H5Sclose;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5S.H5Screate_simple;
import static ch.systemsx.cisd.hdf5.hdf5lib.H5S.H5Sselect_hyperslab;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5F_ACC_TRUNC;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5P_DATASET_CREATE;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5P_DEFAULT;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5S_ALL;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5T_NATIVE_FLOAT;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5T_NATIVE_UINT32;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5T_NATIVE_UINT16;
import static ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants.H5T_NATIVE_UINT8;
import ncsa.hdf.hdf5lib.H5;
import java.util.Arrays;
import org.scijava.log.LogService;
import ch.systemsx.cisd.hdf5.hdf5lib.HDF5Constants;
import static java.lang.Long.min;
import ncsa.hdf.hdf5lib.exceptions.HDF5Exception;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccess;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

public class Hdf5DataSetWriterFromImgPlus<T extends Type<T>> {
    private static final int NUM_OF_ARGB_CHANNELS = 4;
    private ImgPlus<T> image;

    private long nFrames;
    private long nChannels;
    private long nLevs;
    private long nRows;
    private long nCols;

    private LogService log;
    private String filename;
    private String dataset;
    private int compressionLevel;

    private int file_id = -1;
    private int dataspace_id = -1;
    private int dataset_id = -1;
    private int dcpl_id = -1;
    private long[] maxdims = {
            HDF5Constants.H5S_UNLIMITED,
            HDF5Constants.H5S_UNLIMITED,
            HDF5Constants.H5S_UNLIMITED,
            HDF5Constants.H5S_UNLIMITED,
            HDF5Constants.H5S_UNLIMITED
    };

    public Hdf5DataSetWriterFromImgPlus(ImgPlus<T> image, String filename, String dataset, int compressionLevel, LogService log) {
        this.image = image;

        if (image.dimensionIndex(Axes.TIME) >= 0)
            this.nFrames = image.dimension(image.dimensionIndex(Axes.TIME));
        else
            this.nFrames = 1;

        if (image.dimensionIndex(Axes.CHANNEL) >= 0)
            this.nChannels = image.dimension(image.dimensionIndex(Axes.CHANNEL));
        else
            this.nChannels = 1;

        if (image.dimensionIndex(Axes.Z) >= 0)
            this.nLevs = image.dimension(image.dimensionIndex(Axes.Z));
        else
            this.nLevs = 1;

        if (image.dimensionIndex(Axes.X) < 0 || image.dimensionIndex(Axes.X) < 0) {
            throw new IllegalArgumentException("image must have X and Y dimensions!");
        }

        this.nRows = image.dimension(image.dimensionIndex(Axes.Y));
        this.nCols = image.dimension(image.dimensionIndex(Axes.X));
        this.filename = filename;
        this.dataset = dataset;
        this.compressionLevel = compressionLevel;
        this.log = log;
    }

    public void write() {
        long[] chunk_dims = {1,
                min(nLevs, 256),
                min(nRows, 256),
                min(nCols, 256),
                1
        };
        log.info("Export Dimensions in tzyxc: " + String.valueOf(nFrames) + "x" + String.valueOf(nLevs) + "x"
                + String.valueOf(nRows) + "x" + String.valueOf(nCols) + "x" + String.valueOf(nChannels));

        try {
            try {
                file_id = H5Fcreate(filename, H5F_ACC_TRUNC, H5P_DEFAULT, H5P_DEFAULT);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                dcpl_id = H5Pcreate(H5P_DATASET_CREATE);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                H5Pset_chunk(dcpl_id, 5, chunk_dims);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                H5Pset_deflate(dcpl_id, compressionLevel);
            } catch (Exception e) {
                e.printStackTrace();
            }

            T val = image.firstElement();
            if (val instanceof UnsignedByteType) {
                log.info("Writing uint 8");
                writeIndividualChannels(H5T_NATIVE_UINT8);
            } else if (val instanceof UnsignedShortType) {
                log.info("Writing uint 16");
                writeIndividualChannels(H5T_NATIVE_UINT16);
            } else if (val instanceof UnsignedIntType) {
                log.info("Writing uint 32");
                writeIndividualChannels(H5T_NATIVE_UINT32);
            } else if (val instanceof FloatType) {
                log.info("Writing float 32");
                writeIndividualChannels(H5T_NATIVE_FLOAT);
            } else if (val instanceof ARGBType) {
                log.info("Writing ARGB to 4 uint8 channels");
                writeARGB();
            } else {
                log.error("Type Not handled yet!");
            }

            try {
                if (dataspace_id >= 0) {
                    H5Sclose(dataspace_id);
                    dataspace_id = -1;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                H5Pclose(dcpl_id);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                if (dataset_id >= 0)
                    H5Dclose(dataset_id);
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                if (file_id >= 0)
                    H5Fclose(file_id);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (HDF5Exception err) {
            log.error("Error while saving '" + filename + "':\n" + err);
        } catch (Exception err) {
            log.error("Error while saving '" + filename + "':\n" + err);
        } catch (OutOfMemoryError o) {
            log.error("Out of Memory Error while saving '" + filename + "':\n" + o);
        }
    }

    private void writeARGB() {
        long[] channelDimsRGB = new long[5];
        channelDimsRGB[0] = nFrames; //t
        channelDimsRGB[1] = nLevs; //z
        channelDimsRGB[2] = nRows; //y
        channelDimsRGB[3] = nCols; //x
        channelDimsRGB[4] = NUM_OF_ARGB_CHANNELS;//c

        long[] colorIniDims = new long[5];
        colorIniDims[0] = 1;
        colorIniDims[1] = 1;
        colorIniDims[2] = nRows;
        colorIniDims[3] = nCols;
        colorIniDims[4] = 1;

        try {
            dataspace_id = H5.H5Screate_simple(5, colorIniDims, maxdims);
        } catch (Exception e) {
            log.error("H5D dataspace creation failed", e);
            throw e;
        }

        try {
            if ((file_id >= 0) && (dataspace_id >= 0))
                dataset_id = H5.H5Dcreate(file_id, dataset, H5T_NATIVE_UINT8, dataspace_id, H5P_DEFAULT, dcpl_id, H5P_DEFAULT);
        } catch (Exception e) {
            log.error("H5D dataset creation failed", e);
            throw e;
        }

        RandomAccess<ARGBType> rai = (RandomAccess<ARGBType>) image.randomAccess();
        boolean isFirstSlice = true;
        boolean isAlphaChannelPresent = true;
        byte[][] pixelsByte;
        final int cols = Math.toIntExact(nCols);
        final int rows = Math.toIntExact(nRows);

        for (long t = 0; t < nFrames; t++) {
            if (image.dimensionIndex(Axes.TIME) >= 0)
                rai.setPosition(t, image.dimensionIndex(Axes.TIME));

            for (long z = 0; z < nLevs; z++) {
                if (image.dimensionIndex(Axes.Z) >= 0)
                    rai.setPosition(z, image.dimensionIndex(Axes.Z));

                if (nChannels == NUM_OF_ARGB_CHANNELS - 1) {
                    log.warn("Only 3 channel RGB found. Setting ALPHA channel to -1 (transparent).");
                    isAlphaChannelPresent = false;
                }
                for (long c = 0; c < NUM_OF_ARGB_CHANNELS; c++) {
                    // Construct 2D array of appropriate data
                    pixelsByte = new byte[rows][cols];
                    if(!isAlphaChannelPresent) {
                        if (c == 0) {
                            for (byte[] row :  pixelsByte) {
                                Arrays.fill(row, (byte) -1);  // hard code alpha channel.
                            }
                        } else {
                            if(image.dimensionIndex(Axes.CHANNEL) >= 0) {
                                rai.setPosition(c-1, image.dimensionIndex(Axes.CHANNEL));
                            }
                            fillByteSliceARGB(rai, pixelsByte);
                        }
                    }else {
                        if (image.dimensionIndex(Axes.CHANNEL) >= 0) {
                            rai.setPosition(c, image.dimensionIndex(Axes.CHANNEL));
                        }
                        fillByteSliceARGB(rai, pixelsByte);
                    }

                    // write it out

                    if (isFirstSlice) {
                        try {
                            if (dataset_id >= 0) {

                                H5.H5Dwrite(dataset_id, H5T_NATIVE_UINT8, H5S_ALL, H5S_ALL, H5P_DEFAULT, pixelsByte);
                            } else {
                                throw new HDF5Exception("No active dataset for writing first chunk");
                            }
                        } catch (Exception e) {
                            log.error("H5D write failed", e);
                            throw e;
                        }
                        try {
                            if (dataspace_id >= 0) {
                                H5.H5Sclose(dataspace_id);
                                dataspace_id = -1;
                            }
                        } catch (Exception e) {
                            log.error("H5D close failed.", e);
                        }


                        try {
                            if (dataset_id >= 0)
                                H5.H5Dset_extent(dataset_id, channelDimsRGB);
                        } catch (Exception e) {
                            log.error("H5D extension failed.", e);
                        }
                        isFirstSlice = false;

                    } else {
                        try {
                            if (dataset_id >= 0) {
                                dataspace_id = H5.H5Dget_space(dataset_id);
                                long[] start = {t, z, 0, 0, c}; // tzyxc
                                H5.H5Sselect_hyperslab(dataspace_id, HDF5Constants.H5S_SELECT_SET, start, null, colorIniDims, null);
                                int memSpace = H5Screate_simple(5, colorIniDims, null);
                                if (dataspace_id >= 0) {
                                    H5.H5Dwrite(dataset_id, H5T_NATIVE_UINT8, memSpace, dataspace_id, H5P_DEFAULT, pixelsByte);
                                } else {
                                    throw new HDF5Exception("No active dataset for writing remaining");
                                }
                            }
                        } catch (Exception e) {
                            log.error("H5D write failed", e);
                            throw e;
                        }
                    }
                }
            }
        }
        log.info("write uint8 RGB HDF5");
        log.info("compressionLevel: " + String.valueOf(compressionLevel));
        log.info("Done");
    }


    private void writeIndividualChannels(int hdf5DataType) {
        if (nLevs < 1) {
            log.error("got less than 1 z?");
            return;
        }
        long[] channel_Dims = new long[5];
        channel_Dims[0] = nFrames; // t
        channel_Dims[1] = nLevs; // z
        channel_Dims[2] = nRows; //y
        channel_Dims[3] = nCols; //x
        channel_Dims[4] = nChannels; // c

        long[] iniDims = new long[5];
        iniDims[0] = 1;
        iniDims[1] = 1;
        iniDims[2] = nRows;
        iniDims[3] = nCols;
        iniDims[4] = 1;

        try {
            dataspace_id = H5Screate_simple(5, iniDims, maxdims);
        } catch (Exception e) {
            e.printStackTrace();
        }

        try {
            if ((file_id >= 0) && (dataspace_id >= 0))
                dataset_id = H5Dcreate(file_id, dataset, hdf5DataType, dataspace_id, H5P_DEFAULT, dcpl_id, H5P_DEFAULT);
        } catch (Exception e) {
            e.printStackTrace();
        }

        RandomAccess<T> rai = image.randomAccess();
        boolean isFirstSlice = true;
        byte[] pixels_byte = null;
        short[] pixels_short = null;
        int[] pixels_int = null;
        float[] pixels_float = null;

        for (long t = 0; t < nFrames; t++) {
            if (image.dimensionIndex(Axes.TIME) >= 0)
                rai.setPosition(t, image.dimensionIndex(Axes.TIME));

            for (long z = 0; z < nLevs; z++) {
                if (image.dimensionIndex(Axes.Z) >= 0)
                    rai.setPosition(z, image.dimensionIndex(Axes.Z));

                for (long c = 0; c < nChannels; c++) {
                    if (image.dimensionIndex(Axes.CHANNEL) >= 0)
                        rai.setPosition(c, image.dimensionIndex(Axes.CHANNEL));

                    // Construct 2D array of appropriate data
                    if (hdf5DataType == H5T_NATIVE_UINT8) {
                        pixels_byte = new byte[(int) (nCols * nRows)];
                        fillByteSlice(rai, pixels_byte);
                    } else if (hdf5DataType == H5T_NATIVE_UINT16) {
                        pixels_short = new short[(int) (nCols * nRows)];
                        fillShortSlice(rai, pixels_short);
                    } else if (hdf5DataType == H5T_NATIVE_UINT32) {
                        pixels_int = new int[(int) (nCols * nRows)];
                        fillIntSlice(rai, pixels_int);
                    } else if (hdf5DataType == H5T_NATIVE_FLOAT) {
                        pixels_float = new float[(int) (nCols * nRows)];
                        fillFloatSlice(rai, pixels_float);
                    } else {
                        throw new IllegalArgumentException("Trying to save dataset of unknown datatype");
                    }

                    // write it out
                    if (isFirstSlice) {
                        try {
                            if (dataset_id >= 0) {
                                if (hdf5DataType == H5T_NATIVE_UINT8) {
                                    H5Dwrite(dataset_id, hdf5DataType, H5S_ALL, H5S_ALL, H5P_DEFAULT, pixels_byte);
                                } else if (hdf5DataType == H5T_NATIVE_UINT16) {
                                    H5Dwrite(dataset_id, hdf5DataType, H5S_ALL, H5S_ALL, H5P_DEFAULT, pixels_short);
                                } else if (hdf5DataType == H5T_NATIVE_UINT32) {
                                    H5Dwrite(dataset_id, hdf5DataType, H5S_ALL, H5S_ALL, H5P_DEFAULT, pixels_int);
                                } else if (hdf5DataType == H5T_NATIVE_FLOAT) {
                                    H5Dwrite(dataset_id, hdf5DataType, H5S_ALL, H5S_ALL, H5P_DEFAULT, pixels_float);
                                }
                            } else
                                throw new HDF5Exception("No active dataset for writing first chunk");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        try {
                            if (dataspace_id >= 0) {
                                H5Sclose(dataspace_id);
                                dataspace_id = -1;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        try {
                            if (dataset_id >= 0)
                                H5Dset_extent(dataset_id, channel_Dims);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        isFirstSlice = false;
                    } else {
                        try {
                            if (dataset_id >= 0) {
                                dataspace_id = H5Dget_space(dataset_id);
                                long[] start = {t, z, 0, 0, c}; // tzyxc
                                H5Sselect_hyperslab(dataspace_id, HDF5Constants.H5S_SELECT_SET, start, null, iniDims, null);

                                int memspace = H5Screate_simple(5, iniDims, null);
                                if (dataspace_id >= 0) {
                                    if (hdf5DataType == H5T_NATIVE_UINT8) {
                                        H5Dwrite(dataset_id, hdf5DataType, memspace, dataspace_id, H5P_DEFAULT, pixels_byte);
                                    } else if (hdf5DataType == H5T_NATIVE_UINT16) {
                                        H5Dwrite(dataset_id, hdf5DataType, memspace, dataspace_id, H5P_DEFAULT, pixels_short);
                                    } else if (hdf5DataType == H5T_NATIVE_UINT32) {
                                        H5Dwrite(dataset_id, hdf5DataType, memspace, dataspace_id, H5P_DEFAULT, pixels_int);
                                    } else if (hdf5DataType == H5T_NATIVE_FLOAT) {
                                        H5Dwrite(dataset_id, hdf5DataType, memspace, dataspace_id, H5P_DEFAULT, pixels_float);
                                    }
                                } else
                                    throw new HDF5Exception("No active dataset for writing remaining");
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        log.info("write hdf5");
        log.info("Done");
    }

    private void fillFloatSlice(RandomAccess<T> rai, float[] pixels_float) {
        for (long x = 0; x < nCols; x++) {
            rai.setPosition(x, image.dimensionIndex(Axes.X));
            for (long y = 0; y < nRows; y++) {
                rai.setPosition(y, image.dimensionIndex(Axes.Y));
                T value = rai.get();
                pixels_float[(int) (y * nCols + x)] = ((FloatType) value).get();
            }
        }
    }

    private void fillIntSlice(RandomAccess<T> rai, int[] pixels_int) {
        for (long x = 0; x < nCols; x++) {
            rai.setPosition(x, image.dimensionIndex(Axes.X));
            for (long y = 0; y < nRows; y++) {
                rai.setPosition(y, image.dimensionIndex(Axes.Y));
                T value = rai.get();
                pixels_int[(int) (y * nCols + x)] = (int) ((UnsignedIntType) value).get();
            }
        }
    }

    private void fillShortSlice(RandomAccess<T> rai, short[] pixels_short) {
        for (long x = 0; x < nCols; x++) {
            rai.setPosition(x, image.dimensionIndex(Axes.X));
            for (long y = 0; y < nRows; y++) {
                rai.setPosition(y, image.dimensionIndex(Axes.Y));
                T value = rai.get();
                pixels_short[(int) (y * nCols + x)] = (short) (((UnsignedShortType) value).get());
            }
        }
    }

    private void fillByteSlice(RandomAccess<T> rai, byte[] pixels_byte) {
        for (long x = 0; x < nCols; x++) {
            rai.setPosition(x, image.dimensionIndex(Axes.X));
            for (long y = 0; y < nRows; y++) {
                rai.setPosition(y, image.dimensionIndex(Axes.Y));
                T value = rai.get();
                pixels_byte[(int) (y * nCols + x)] = (byte) (((UnsignedByteType) value).get());
            }
        }
    }

    private void fillByteSliceARGB(RandomAccess<ARGBType> rai, byte[][] pixelsByte) {
        final int cols = Math.toIntExact(nCols);
        final int rows = Math.toIntExact(nRows);

        for (int x = 0; x < cols; x++) {
            rai.setPosition(x, image.dimensionIndex(Axes.X));
            for (int y = 0; y < rows; y++) {
                rai.setPosition(y, image.dimensionIndex(Axes.Y));
                ARGBType value = rai.get();
                pixelsByte[y][x] = (byte) value.get();
            }
        }
    }

}
